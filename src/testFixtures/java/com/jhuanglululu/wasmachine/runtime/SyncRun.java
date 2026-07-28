package com.jhuanglululu.wasmachine.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jhuanglululu.wasm.Module;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Drives a {@link SyncWasm} module to its end and collects its log trace.
 *
 * <p>The convenience entry points run the module on a bare {@link MachineInstance} with no-op
 * plugin imports — everything an engine test needs. An embedder whose own imports are under
 * test drives its own instance through {@link #drive} instead, so both sides share one set of
 * outcome assertions.
 */
public final class SyncRun {

    private SyncRun() {}

    /** The instance name and handshake every fixture run uses (the ABI 3 engine names). */
    private static final String NAME = "sync";
    private static final String ENTRY = "_engine_main";
    private static final String ABI = "_engine_abi";

    /** The engine handshake every fixture module satisfies. */
    public static final MachineInstance.AbiCheck ENGINE_ABI = new MachineInstance.AbiCheck(
            ABI, MachineInstance.ENGINE_ABI_VERSION, MachineInstance.ENGINE_ABI_VERSION);

    /** How a run ended, independent of whose {@code TickResult} type reported it. */
    public record Outcome(boolean finished, String message) {

        /** Task 0 returned. */
        public static Outcome ofFinished() {
            return new Outcome(true, null);
        }

        /** The run was killed with {@code message}. */
        public static Outcome ofErrored(String message) {
            return new Outcome(false, message);
        }
    }

    /** Ticks one instance; returns {@code null} while it is still running. */
    @FunctionalInterface
    public interface Ticker {
        Outcome tick(long currentTick, long fuelBudget);
    }

    /** Runs with a 1 MiB cap, instance seed 0, 40 ticks and a generous fuel budget. */
    public static Result run(SyncWasm.P main) {
        return run(main, 1 << 20, 0L, 40, 10_000_000L);
    }

    /** Runs with instance seed {@code seed} (which selects the {@code notify_one(Random)} draw). */
    public static Result seeded(SyncWasm.P main, long seed) {
        return run(main, 1 << 20, seed, 40, 10_000_000L);
    }

    public static Result run(SyncWasm.P main, long memoryCap, long seed, int maxTicks, long budget) {
        List<String> logs = new ArrayList<>();
        MachineInstance instance = new MachineInstance(Module.parse(SyncWasm.module(main)),
                new MachineInstance.Config(NAME, RuntimeWasm.ENGINE_MODULE, ENTRY,
                        List.of(ENGINE_ABI), memoryCap, seed),
                (name, message) -> logs.add(message), SyncWasm.stubPluginImports());
        return drive((tick, fuel) -> outcomeOf(instance.tick(tick, fuel)), logs, maxTicks, budget);
    }

    /** The {@link Outcome} an engine {@link TickResult} reports, or {@code null} while running. */
    public static Outcome outcomeOf(TickResult result) {
        return switch (result) {
            case TickResult.Running ignored -> null;
            case TickResult.Finished ignored -> Outcome.ofFinished();
            case TickResult.Errored e -> Outcome.ofErrored(e.message());
        };
    }

    /**
     * Ticks {@code ticker} from tick 0 until it stops running or {@code maxTicks} elapse,
     * pairing the outcome with the log lines the run produced.
     */
    public static Result drive(Ticker ticker, List<String> logs, int maxTicks, long fuelBudget) {
        Outcome outcome = null;
        for (long tick = 0; tick < maxTicks; tick++) {
            outcome = ticker.tick(tick, fuelBudget);
            if (outcome != null) {
                break;
            }
        }
        return new Result(outcome, logs);
    }

    /** How a run ended ({@code null} means it was still running when the ticks ran out). */
    public record Result(Outcome outcome, List<String> logs) {

        /** The logged characters concatenated, e.g. {@code "ABC"} — the observable ordering. */
        public String trace() {
            return String.join("", logs);
        }

        public Result assertFinished() {
            assertTrue(outcome != null && outcome.finished(),
                    "expected the run to finish but got " + describe() + " (trace " + trace() + ")");
            return this;
        }

        /** Asserts the run was killed with a message containing every given substring. */
        public void assertKilled(String... needles) {
            assertTrue(outcome != null && !outcome.finished(),
                    "expected the run to be killed but got " + describe());
            String message = outcome.message();
            for (String needle : needles) {
                assertTrue(message.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT)),
                        "expected the kill message to contain \"" + needle + "\" but was: " + message);
            }
        }

        private String describe() {
            if (outcome == null) {
                return "a run still going after its last tick";
            }
            return outcome.finished() ? "Finished" : "Errored(" + outcome.message() + ")";
        }
    }
}
