package com.jhuanglululu.wasmachine.runtime;

import com.jhuanglululu.wasm.ExecResult;
import com.jhuanglululu.wasm.ExecutionContext;
import com.jhuanglululu.wasm.Export;
import com.jhuanglululu.wasm.ExternalKind;
import com.jhuanglululu.wasm.HostFunction;
import com.jhuanglululu.wasm.Instance;
import com.jhuanglululu.wasm.Module;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One running guest: it owns the WASM {@link Instance}, the cooperative tasks that run on it
 * (each an {@link ExecutionContext} with its own memory + {@link HostAllocator}, a wake
 * condition, and a spawn-order index), the {@link SyncTable}, the shared {@link MemoryBudget}
 * and the two random streams. It implements every engine-owned ABI import (realloc, fork, join,
 * kill, exit, sleep, log, fail, sync, random and the {@link MathKernel}), registers the
 * embedder's own import modules next to them, and drives execution one game tick at a time via
 * {@link #tick}.
 *
 * <p><b>Scheduling.</b> Each tick, every due task runs (to its next blocking point) in
 * <em>spawn order</em>, sharing one instruction budget. A task blocks at {@code sleep}
 * (wakes at {@code currentTick + ticks}) or {@code join} (wakes when its target ends).
 * A forked child becomes runnable immediately and runs the same tick, after the parent
 * yields. Task 0's entry export returning ends the whole instance; any other task
 * returning or calling {@code exit} just ends that task.
 *
 * <p><b>Design decisions.</b>
 * <ul>
 *   <li><b>{@code sleep(0)}</b> wakes on the <em>next</em> tick ({@code currentTick + 1}),
 *       not the same tick: a cooperative yield that always advances the clock, so a task
 *       can never busy-spin within a tick holding the shared budget. Positive sleeps are
 *       exact ({@code currentTick + ticks}).</li>
 *   <li><b>Shared budget.</b> The per-tick budget is spent across all tasks in the order
 *       they run; if any task cannot reach a blocking point before it is exhausted, the
 *       whole instance errors (this is the runaway-loop guard).</li>
 *   <li><b>Fork ids.</b> {@code fork} returns the child's task id in the parent and
 *       {@code 0} in the child (Linux semantics). The child is a deep copy of the parent
 *       taken at the fork suspension, resumed with {@code 0}.</li>
 *   <li><b>Releases land in the next round.</b> A tick runs tasks in rounds: each round
 *       gives every due task one turn in spawn order. A task released by a sync operation
 *       (see {@link SyncTable}) is deliberately held back from the round that released it,
 *       so all tasks freed by one barrier completion or one {@code notify_all} take their
 *       turns together, in spawn order, in the following round.</li>
 * </ul>
 *
 * <p><b>What the engine does not decide.</b> {@link TickResult.Finished} carries the raw
 * {@code i32} the entry export returned; interpreting it is the embedder's business. So is
 * every non-engine import: they arrive as {@code pluginImports} and are registered beside the
 * engine's own, which is the whole anti-patching design — a new plugin capability is a new
 * entry in the plugin's module, never an engine edit.
 *
 * <p>Not thread-safe: an instance is confined to one worker at a time.
 */
public final class MachineInstance {

    /**
     * The version of the engine-owned ABI this build speaks: what a guest's {@code _engine_abi()}
     * must return. Additive import growth (a new math kernel entry, say) does not bump it — an
     * older guest simply never imports the new name; semantic changes do. Plugin modules version
     * independently through their own handshake export.
     */
    public static final int ENGINE_ABI_VERSION = 1;

    /**
     * One load-time handshake: {@code export} is invoked with no arguments and must return an
     * {@code i32} version within {@code minVersion..maxVersion}. Every check runs at
     * construction, so no mismatch waits for first use.
     */
    public record AbiCheck(String export, int minVersion, int maxVersion) {}

    /**
     * Everything an instance needs that is not the module itself or its imports.
     *
     * @param name           the instance name (for log routing)
     * @param engineModule   the import module name the engine registers its own functions under
     * @param entryExport    the exported function task 0 runs (no arguments, one {@code i32})
     * @param abiChecks      the handshake exports validated at construction
     * @param memoryCapBytes the per-instance memory cap: one allowance shared by every task's
     *                       heap and by the channel buffers (see {@link MemoryBudget})
     * @param instanceSeed   seeds this instance's deterministic random stream
     */
    public record Config(String name, String engineModule, String entryExport,
            List<AbiCheck> abiChecks, long memoryCapBytes, long instanceSeed) {

        public Config {
            abiChecks = List.copyOf(abiChecks);
        }
    }

    private static final long[] NO_ARGS = new long[0];

    // Splits the scheduling random stream off the instance seed (an arbitrary odd constant).
    private static final long SCHEDULING_STREAM_SALT = 0xD1B54A32D192ED03L;

    // Suspension request payloads a host import hands to the interpreter.
    private sealed interface Request
            permits SleepRequest, JoinRequest, ForkRequest, ExitRequest, ParkRequest {}

    private record SleepRequest(long ticks) implements Request {}

    private record JoinRequest(int taskId) implements Request {}

    private record ForkRequest() implements Request {}

    private record ExitRequest() implements Request {}

    /** Parked on a sync object; the {@link SyncTable} decides when (and with what) it resumes. */
    private record ParkRequest() implements Request {}

    private static final ForkRequest FORK = new ForkRequest();
    private static final ExitRequest EXIT = new ExitRequest();
    private static final ParkRequest PARK = new ParkRequest();

    private enum TaskState {
        /** Task 0 before its entry export has been invoked. */
        NOT_STARTED,
        /** A forked child awaiting its first resume (which returns {@code 0} from fork). */
        FORK_PENDING,
        /** Ready to run/continue now. */
        RUNNABLE,
        /** Parked until {@link Task#wakeTick}. */
        SLEEPING,
        /** Parked until {@link Task#joinTarget} finishes. */
        JOINING,
        /** Parked on a sync object until the {@link SyncTable} releases it. */
        PARKED,
        /** Ended (returned, exited, or killed). */
        FINISHED
    }

    private static final class Task {
        final int id;
        ExecutionContext ctx;
        HostAllocator allocator;
        TaskState state;
        long wakeTick;
        int joinTarget = -1;
        // Set by the sync waker: the value the parked host call returns, and whether the
        // release happened before the park suspension was even recorded (same-turn release).
        long resumeValue;
        boolean parkReleased;

        Task(int id) {
            this.id = id;
        }
    }

    private final Config config;
    private final LogSink logSink;

    private final Instance wasm;
    private final SyncTable sync;
    // One allowance for every guest heap and every channel buffer in this instance.
    private final MemoryBudget budget;
    // The guest-facing deterministic random stream; seed_random restarts it.
    private final SplitMix64 deterministicRandom;
    // In ascending spawn order: task 0 first, forked children appended as created.
    private final List<Task> tasks = new ArrayList<>();
    // Tasks a sync release woke during the current scheduling round: they wait for the next
    // one, so everything freed by a single release takes its turn in spawn order.
    private final Set<Integer> releasedThisRound = new HashSet<>();

    private int nextTaskId = 1;

    private Task currentTask;
    private long remainingFuel;
    private long tickBudget;

    // How many times the guest drew from the non-deterministic stream. A guest that means to
    // be reproducible must never touch it, and this is the only way to tell from outside: the
    // deterministic and non-deterministic imports are both linked whenever either can be reached.
    private long nonDeterministicDraws;

    private TickResult terminal; // set once the instance ends (Finished/Errored)

    /**
     * Instantiates {@code module}, creates task 0, and runs every configured ABI handshake.
     * Construction runs no guest code beyond the handshake exports: task 0's entry export is
     * only invoked by {@link #tick}, so an embedder can build an instance purely to call
     * {@link #loadError()}.
     *
     * @param pluginImports the embedder's import modules: module name → function name → impl.
     *                      Engine-owned names in {@code config.engineModule()} win a collision.
     */
    public MachineInstance(Module module, Config config, LogSink logSink,
            Map<String, Map<String, HostFunction>> pluginImports) {
        this.config = config;
        this.logSink = logSink;
        this.deterministicRandom = new SplitMix64(config.instanceSeed());
        this.budget = new MemoryBudget(config.memoryCapBytes());
        // The scheduling stream is derived from the same seed but never shares state with the
        // guest-facing one, so cosmetic random() calls cannot reshuffle notify_one(Random).
        this.sync = new SyncTable(this::releaseTask,
                config.instanceSeed() ^ SCHEDULING_STREAM_SALT, budget);
        this.wasm = new Instance(module, buildImports(pluginImports));

        ExecutionContext ctx0 = wasm.instantiate();
        int heapBase = exportedGlobalI32(module, ctx0, "__heap_base");

        Task task0 = new Task(0);
        task0.ctx = ctx0;
        task0.allocator = new HostAllocator(heapBase, budget);
        task0.state = TaskState.NOT_STARTED;
        task0.wakeTick = 0;
        tasks.add(task0);

        validateAbi(ctx0);
    }

    private void validateAbi(ExecutionContext ctx0) {
        currentTask = tasks.get(0);
        for (AbiCheck check : config.abiChecks()) {
            if (!validateOne(ctx0, check)) {
                return;
            }
        }
    }

    /** Runs one handshake export; returns false (and records the terminal) if it failed. */
    private boolean validateOne(ExecutionContext ctx0, AbiCheck check) {
        try {
            ExecResult r = wasm.invoke(ctx0, check.export(), NO_ARGS, 1_000_000);
            if (!(r instanceof ExecResult.Completed c) || c.values().length != 1) {
                this.terminal = new TickResult.Errored(
                        "ABI handshake failed: " + check.export() + " did not return a version");
                return false;
            }
            int version = (int) c.values()[0];
            if (version < check.minVersion() || version > check.maxVersion()) {
                this.terminal = new TickResult.Errored("ABI handshake failed: " + check.export()
                        + " returned " + version + " but this host speaks "
                        + check.minVersion() + ".." + check.maxVersion());
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            this.terminal = new TickResult.Errored(
                    "module does not export a valid " + check.export() + "(): " + e.getMessage());
            return false;
        }
    }

    private static int exportedGlobalI32(Module module, ExecutionContext ctx, String exportName) {
        for (Export e : module.exports()) {
            if (e.kind() == ExternalKind.GLOBAL && e.name().equals(exportName)) {
                return (int) ctx.readGlobal(e.index());
            }
        }
        throw new IllegalArgumentException("module has no exported global \"" + exportName + "\"");
    }

    /** The instance name. */
    public String name() {
        return config.name();
    }

    /**
     * The reason this instance is unusable, if construction already decided it — today only a
     * failed ABI handshake. Load-time validation builds an instance purely to ask this, so the
     * check a server start performs is exactly the one a real start performs; empty means the
     * module is fit to run.
     */
    public Optional<String> loadError() {
        return terminal instanceof TickResult.Errored e ? Optional.of(e.message()) : Optional.empty();
    }

    /**
     * How many times this guest has drawn from the non-deterministic random stream. Zero means
     * every random value it produced is reproducible from its instance seed.
     */
    public long nonDeterministicDraws() {
        return nonDeterministicDraws;
    }

    /**
     * Advances the guest by one game tick: resumes every due task in spawn order,
     * sharing {@code fuelBudget} instructions across them.
     *
     * @param currentTick the current game tick (monotonic)
     * @param fuelBudget  the instruction budget for this tick, across all tasks
     * @return {@link TickResult.Running}, {@link TickResult.Finished}, or {@link TickResult.Errored}
     */
    public TickResult tick(long currentTick, long fuelBudget) {
        if (terminal != null) {
            return terminal;
        }
        this.tickBudget = fuelBudget;
        this.remainingFuel = fuelBudget;
        releasedThisRound.clear();
        try {
            List<Integer> ranThisRound = new ArrayList<>();
            boolean ranSinceClear = false;
            while (true) {
                Task cand = pickRunnable(currentTick, ranThisRound);
                if (cand == null) {
                    if (!ranSinceClear) {
                        break;
                    }
                    ranThisRound.clear();
                    releasedThisRound.clear();
                    ranSinceClear = false;
                    continue;
                }
                ranThisRound.add(cand.id);
                ranSinceClear = true;
                TickResult r = runTurn(cand, currentTick);
                if (r != null) {
                    this.terminal = r;
                    return r;
                }
            }
        } catch (GuestAbort e) {
            this.terminal = new TickResult.Errored(e.getMessage());
            return this.terminal;
        }
        return new TickResult.Running();
    }

    private Task pickRunnable(long currentTick, List<Integer> ranThisRound) {
        for (Task t : tasks) { // tasks are in ascending spawn-index order
            if (t.state != TaskState.FINISHED && !ranThisRound.contains(t.id)
                    && !releasedThisRound.contains(t.id) && dueAt(t, currentTick)) {
                return t;
            }
        }
        return null;
    }

    private boolean dueAt(Task t, long currentTick) {
        return switch (t.state) {
            case NOT_STARTED, FORK_PENDING, RUNNABLE -> true;
            case PARKED -> false;
            case SLEEPING -> t.wakeTick <= currentTick;
            case JOINING -> {
                Task target = byId(t.joinTarget);
                yield target == null || target.state == TaskState.FINISHED;
            }
            case FINISHED -> false;
        };
    }

    private TickResult runTurn(Task task, long currentTick) {
        ExecResult result = startOrResume(task);
        while (result instanceof ExecResult.Suspended s && s.request() instanceof ForkRequest) {
            Task child = doFork(task, currentTick);
            result = resumeWith(task, child.id);
        }
        return applyResult(task, result, currentTick);
    }

    private ExecResult startOrResume(Task task) {
        currentTask = task;
        ExecResult r;
        switch (task.state) {
            case NOT_STARTED -> {
                task.state = TaskState.RUNNABLE;
                r = wasm.invoke(task.ctx, config.entryExport(), NO_ARGS, remainingFuel);
            }
            case FORK_PENDING -> {
                task.state = TaskState.RUNNABLE;
                r = wasm.resume(task.ctx, remainingFuel, 0L);
            }
            default -> {
                task.state = TaskState.RUNNABLE;
                long parkResult = task.resumeValue; // ignored unless the parked call has a result
                task.resumeValue = 0;
                task.parkReleased = false;
                r = wasm.resume(task.ctx, remainingFuel, parkResult);
            }
        }
        remainingFuel -= task.ctx.fuelConsumed();
        return r;
    }

    private ExecResult resumeWith(Task task, long hostResult) {
        currentTask = task;
        ExecResult r = wasm.resume(task.ctx, remainingFuel, hostResult);
        remainingFuel -= task.ctx.fuelConsumed();
        return r;
    }

    private Task doFork(Task parent, long currentTick) {
        Task child = new Task(nextTaskId++);
        child.ctx = parent.ctx.copy();
        child.allocator = parent.allocator.copy();
        child.state = TaskState.FORK_PENDING;
        child.wakeTick = currentTick;
        tasks.add(child);
        return child;
    }

    private TickResult applyResult(Task task, ExecResult result, long currentTick) {
        return switch (result) {
            case ExecResult.Completed c -> onCompleted(task, c);
            case ExecResult.Suspended s -> onSuspended(task, s, currentTick);
            case ExecResult.Trapped t ->
                    new TickResult.Errored("guest trapped: " + t.message());
            case ExecResult.FuelExhausted ignored -> new TickResult.Errored(
                    "instruction budget of " + tickBudget + " exhausted before a blocking point"
                            + " (runaway loop?) in task " + task.id);
        };
    }

    private TickResult onCompleted(Task task, ExecResult.Completed c) {
        if (task.id == 0) {
            // Task 0 returning ends everything; the engine hands the raw i32 back untouched —
            // what it means is the embedder's decision, not the engine's.
            finishAll();
            return new TickResult.Finished((int) c.values()[0]);
        }
        finishTask(task);
        return null;
    }

    private TickResult onSuspended(Task task, ExecResult.Suspended s, long currentTick) {
        if (!(s.request() instanceof Request req)) {
            return new TickResult.Errored("unexpected suspension request: " + s.request());
        }
        return switch (req) {
            case SleepRequest sr -> {
                long t = sr.ticks();
                task.wakeTick = currentTick + (t <= 0 ? 1 : t); // sleep(0) yields to the next tick
                task.state = TaskState.SLEEPING;
                yield null;
            }
            case JoinRequest jr -> {
                Task target = byId(jr.taskId());
                if (target == null) {
                    yield new TickResult.Errored("join on unknown task id " + jr.taskId());
                }
                if (target.state == TaskState.FINISHED) {
                    task.state = TaskState.RUNNABLE; // join returns; runs again this tick
                } else {
                    task.state = TaskState.JOINING;
                    task.joinTarget = jr.taskId();
                }
                yield null;
            }
            case ExitRequest ignored -> {
                if (task.id == 0) {
                    // exit() on task 0 ends the instance with exit value 0.
                    finishAll();
                    yield new TickResult.Finished(0);
                }
                finishTask(task);
                yield null;
            }
            case ParkRequest ignored -> {
                if (task.parkReleased) {
                    // Released by its own sync call (a barrier its arrival completed): it never
                    // really blocks, but still yields, so the whole group resumes in spawn order.
                    task.parkReleased = false;
                    task.state = TaskState.RUNNABLE;
                } else {
                    task.state = TaskState.PARKED;
                }
                yield null;
            }
            // fork is fully handled in runTurn's inline loop before we get here.
            case ForkRequest ignored -> new TickResult.Errored("stray fork suspension");
        };
    }

    /** The {@link SyncTable.Waker}: a released task becomes runnable in the next round. */
    private void releaseTask(int taskId, long resumeValue) {
        Task t = byId(taskId);
        if (t == null || t.state == TaskState.FINISHED) {
            return;
        }
        t.resumeValue = resumeValue;
        t.parkReleased = true;
        if (t.state == TaskState.PARKED) {
            t.state = TaskState.RUNNABLE;
        }
        releasedThisRound.add(taskId);
    }

    private void finishTask(Task task) {
        task.state = TaskState.FINISHED;
        sync.removeTask(task.id);
        task.allocator.releaseAll(); // the task's memory is gone; give its budget back
    }

    private void finishAll() {
        for (Task t : tasks) {
            t.state = TaskState.FINISHED;
        }
    }

    private Task byId(int id) {
        for (Task t : tasks) {
            if (t.id == id) {
                return t;
            }
        }
        return null;
    }

    private void killTask(int id) {
        Task t = byId(id);
        if (t != null) {
            // Killed tasks' destructors never run; host resources they own are orphaned until
            // the embedder's end-of-run cleanup. Sync state is not orphaned though: a task killed
            // while parked must stop being a waiter and give back any barrier arrival it
            // contributed.
            t.state = TaskState.FINISHED;
            sync.removeTask(id);
            t.allocator.releaseAll();
        }
    }

    // --- engine-owned ABI imports ---

    private Map<String, HostFunction> buildImports(
            Map<String, Map<String, HostFunction>> pluginImports) {
        Map<String, HostFunction> m = new HashMap<>();
        // The embedder's modules go in first: the engine's own names are registered on top of
        // them, so a plugin can never shadow a function the scheduler's semantics depend on.
        pluginImports.forEach((module, functions) ->
                functions.forEach((name, fn) -> m.put(module + "." + name, fn)));
        String engine = config.engineModule();
        m.put(engine + ".realloc", (ctx, a) ->
                currentTask.allocator.realloc(ctx, (int) a[0], (int) a[1], (int) a[2], (int) a[3]));
        m.put(engine + ".fork", (ctx, a) -> {
            throw ctx.suspend(FORK);
        });
        m.put(engine + ".join", (ctx, a) -> {
            throw ctx.suspend(new JoinRequest((int) a[0]));
        });
        m.put(engine + ".kill", (ctx, a) -> {
            killTask((int) a[0]);
            return 0L;
        });
        m.put(engine + ".exit", (ctx, a) -> {
            throw ctx.suspend(EXIT);
        });
        m.put(engine + ".sleep", (ctx, a) -> {
            throw ctx.suspend(new SleepRequest(a[0]));
        });
        m.put(engine + ".log", (ctx, a) -> {
            logSink.log(config.name(), Marshal.readString(ctx, (int) a[0], (int) a[1]));
            return 0L;
        });
        m.put(engine + ".fail", (ctx, a) -> {
            throw new GuestAbort(Marshal.readString(ctx, (int) a[0], (int) a[1]));
        });
        addSyncImports(m, engine);
        addRandomImports(m, engine);
        MathKernel.addImports(m, engine);
        return m;
    }

    /** ABI v2 sync imports. Every one of these kills the instance on a bad id or kind. */
    private void addSyncImports(Map<String, HostFunction> m, String engine) {
        m.put(engine + ".signal_new", (ctx, a) -> sync.newSignal());
        m.put(engine + ".signal_notify", (ctx, a) -> {
            sync.notifySignal((int) a[0], (int) a[1]);
            return 0L;
        });
        m.put(engine + ".barrier_new", (ctx, a) -> sync.newBarrier((int) a[0]));
        m.put(engine + ".wait_all", (ctx, a) -> sync.newComposite(true, (int) a[0], (int) a[1]));
        m.put(engine + ".wait_any", (ctx, a) -> sync.newComposite(false, (int) a[0], (int) a[1]));
        m.put(engine + ".wait", (ctx, a) -> {
            // Always suspends: a wait satisfied on the spot is released through the waker, so
            // it yields its turn like every other release instead of running straight on.
            sync.park(currentTask.id, (int) a[0]);
            throw ctx.suspend(PARK);
        });
        m.put(engine + ".channel_new", (ctx, a) -> sync.newChannel((int) a[0]));
        m.put(engine + ".channel_send", (ctx, a) -> blocking(ctx,
                sync.send(currentTask.id, (int) a[0], ctx.readBytes((int) a[1], (int) a[2]))));
        m.put(engine + ".channel_recv_len", (ctx, a) ->
                blocking(ctx, sync.receiveLength(currentTask.id, (int) a[0], false)));
        m.put(engine + ".channel_recv", (ctx, a) -> {
            ctx.writeBytes((int) a[1], sync.receive(currentTask.id, (int) a[0]));
            return 0L;
        });
        m.put(engine + ".channel_peek_len", (ctx, a) ->
                blocking(ctx, sync.receiveLength(currentTask.id, (int) a[0], true)));
        m.put(engine + ".channel_peek", (ctx, a) -> {
            ctx.writeBytes((int) a[1], sync.peek(currentTask.id, (int) a[0]));
            return 0L;
        });
        m.put(engine + ".channel_try_len", (ctx, a) -> sync.tryLength(currentTask.id, (int) a[0]));
        m.put(engine + ".channel_clear", (ctx, a) -> {
            sync.clear((int) a[0]);
            return 0L;
        });
    }

    /** ABI v2 random imports: one non-deterministic stream, one seeded per-instance stream. */
    private void addRandomImports(Map<String, HostFunction> m, String engine) {
        m.put(engine + ".random_nondet", (ctx, a) -> {
            nonDeterministicDraws++;
            return ThreadLocalRandom.current().nextLong();
        });
        m.put(engine + ".random_det", (ctx, a) -> deterministicRandom.nextLong());
        m.put(engine + ".seed_random", (ctx, a) -> {
            deterministicRandom.reseed(a[0]);
            return 0L;
        });
    }

    /** Parks the calling task if the sync operation could not complete inline. */
    private static long blocking(ExecutionContext ctx, SyncTable.SyncOutcome outcome) {
        if (outcome.parked()) {
            throw ctx.suspend(PARK);
        }
        return outcome.value();
    }
}
