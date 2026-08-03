package com.jhuanglululu.wasmachine.runtime;

import com.jhuanglululu.wasm.Buf;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Hand-rolled WebAssembly modules for the engine imports — tasks, sync, random, environ and the
 * math kernel. Like {@link RuntimeWasm} it writes section framing by hand, so the tests exercise
 * the real interpreter and the real host-import path rather than a stand-in for them.
 *
 * <p>Every module has the same shape: one import table (indices are the {@code *} constants
 * below), a 1-page memory whose first 26 bytes are {@code "ABC…Z"} (so {@code log(i, 1)}
 * prints the {@code i}-th letter and {@code channel_send(ch, i, 1)} sends it), scratch space
 * from byte 64 up, a function table holding the spawnable task bodies, an exported mutable
 * {@code __stack_pointer} (engine ABI 2 requires it), and a {@code _engine_main} body supplied
 * by the test through {@link P}. {@code _engine_abi} returns
 * {@link MachineInstance#ENGINE_ABI_VERSION}.
 *
 * <p><b>Tasks.</b> Since ABI 2 a child is not a copy of its parent: it is a separate function,
 * entered as {@code fn(i32)} through the function table, with its own stack and its own locals.
 * So {@link P#child(int, P)} takes the local whose value should be handed to the child as its
 * argument — the child reads it as local 0 — and anything else a child needs travels through
 * the (now shared) linear memory, via {@link P#store} / {@link P#load}. Table indices are
 * allocated when the module is built, not when the body is written, so a child registered inside
 * a nested {@link P} still gets the right index: {@link P#child} emits a one-byte placeholder
 * and records its offset for patching (which is also why a fixture may declare at most
 * {@value #MAX_TASKS} task bodies).
 *
 * <p>The engine does not know any embedder's vocabulary — the namespace split made that
 * boundary structural. An embedder whose own imports are under test appends them through a
 * {@link Surface}: its imports land after the engine's in the one import table, declared
 * against the embedder's module, and its handshake export is emitted beside the engine's.
 */
public final class SyncWasm {

    private SyncWasm() {}

    // Import indices, in declaration order (what `call` takes).
    public static final int LOG = 0;
    public static final int SPAWN = 1;
    public static final int SLEEP = 2;
    public static final int EXIT = 3;
    public static final int JOIN = 4;
    public static final int KILL = 5;
    public static final int SIGNAL_NEW = 6;
    public static final int SIGNAL_NOTIFY = 7;
    public static final int BARRIER_NEW = 8;
    public static final int WAIT_ALL = 9;
    public static final int WAIT_ANY = 10;
    public static final int WAIT = 11;
    public static final int CHANNEL_NEW = 12;
    public static final int CHANNEL_SEND = 13;
    public static final int CHANNEL_RECV_LEN = 14;
    public static final int CHANNEL_RECV = 15;
    public static final int CHANNEL_PEEK_LEN = 16;
    public static final int CHANNEL_PEEK = 17;
    public static final int CHANNEL_TRY_LEN = 18;
    public static final int CHANNEL_CLEAR = 19;
    public static final int RANDOM_NONDET = 20;
    public static final int RANDOM_DET = 21;
    public static final int SEED_RANDOM = 22;
    public static final int FAIL = 23;
    public static final int REALLOC = 24;
    // The engine math kernel (guest-abi.md).
    public static final int CBRT = 25;
    public static final int POW = 26;
    public static final int EXP = 27;
    public static final int LN = 28;
    public static final int LOG10 = 29;
    public static final int SIN = 30;
    public static final int COS = 31;
    public static final int TAN = 32;
    public static final int ASIN = 33;
    public static final int ACOS = 34;
    public static final int ATAN2 = 35;
    public static final int FORMAT_F64 = 36;
    public static final int ENVIRON_LEN = 37;
    public static final int ENVIRON_READ = 38;

    /** Where a {@link Surface}'s imports start: the engine's own end at this index. */
    public static final int ENGINE_IMPORT_COUNT = 39;

    private static final int ENGINE_TYPE_COUNT = 13;

    /** Type index of the task-entry signature {@code (i32) -> ()}. */
    private static final int TASK_TYPE = 4;

    /** Scratch address for received channel payloads (well clear of the letter table). */
    public static final int SCRATCH = 64;

    /**
     * Scratch address for values passed between tasks. Where a fork-era fixture relied on the
     * child inheriting the parent's locals, an ABI-2 fixture puts the value here and the child
     * reads it out of the shared memory.
     */
    public static final int VARS = 128;

    /** Global index of the exported mutable {@code __stack_pointer}. */
    public static final int STACK_POINTER_GLOBAL = 1;

    /** A task index has to fit a one-byte {@code i32.const} placeholder. */
    public static final int MAX_TASKS = 63;

    /**
     * An instruction-sequence builder. In {@code _engine_main}, locals 0..7 are {@code i32} and
     * 8..9 are {@code i64}. In a task body, local 0 is the {@code i32} the spawner passed as
     * {@code data}, locals 1..8 are {@code i32} scratch and 9..10 are {@code i64}. The body's
     * trailing {@code end} (and, for main, {@code i32.const 0}) is added by {@link #module}.
     */
    public static final class P {

        /** A one-byte table index placeholder to patch once indices are assigned. */
        private record Fix(int offset, P task) {}

        private final Buf b = new Buf();
        private final List<Fix> fixes = new ArrayList<>();

        public P raw(int... bytes) {
            b.raw(bytes);
            return this;
        }

        /** {@code i32.const} */
        public P i32(int v) {
            b.raw(0x41).sleb(v);
            return this;
        }

        /** {@code i64.const} */
        public P i64(long v) {
            b.raw(0x42).sleb(v);
            return this;
        }

        public P call(int importIndex) {
            b.raw(0x10).uleb(importIndex);
            return this;
        }

        public P get(int local) {
            b.raw(0x20).uleb(local);
            return this;
        }

        public P set(int local) {
            b.raw(0x21).uleb(local);
            return this;
        }

        public P tee(int local) {
            b.raw(0x22).uleb(local);
            return this;
        }

        public P drop() {
            return raw(0x1A);
        }

        /** {@code global.get} */
        public P globalGet(int index) {
            b.raw(0x23).uleb(index);
            return this;
        }

        /** {@code global.set} */
        public P globalSet(int index) {
            b.raw(0x24).uleb(index);
            return this;
        }

        /** {@code i32.store} of {@code local} at the constant address {@code addr}. */
        public P store(int addr, int local) {
            return i32(addr).get(local).raw(0x36, 0x02, 0x00);
        }

        /** {@code i32.store} of the value on top of the stack, at the address below it. */
        public P storeTop() {
            return raw(0x36, 0x02, 0x00);
        }

        /** {@code i32.load} from the constant address {@code addr}. */
        public P load(int addr) {
            return i32(addr).raw(0x28, 0x02, 0x00);
        }

        /** {@code i32.load} from the address on top of the stack. */
        public P loadTop() {
            return raw(0x28, 0x02, 0x00);
        }

        /** {@code memory.size} in pages. */
        public P memorySize() {
            return raw(0x3F, 0x00);
        }

        /** {@code memory.grow} by the page count on top of the stack. */
        public P memoryGrow() {
            return raw(0x40, 0x00);
        }

        /** {@code i32.add} */
        public P add() {
            return raw(0x6A);
        }

        /** {@code i32.sub} */
        public P sub() {
            return raw(0x6B);
        }

        /** Logs the {@code index}-th letter of the alphabet. */
        public P log(int index) {
            return i32(index).i32(1).call(LOG);
        }

        /** Logs {@code lenLocal} bytes at {@code ptr} (for received channel payloads). */
        public P logBytes(int ptr, int lenLocal) {
            return i32(ptr).get(lenLocal).call(LOG);
        }

        public P sleep(long ticks) {
            return i64(ticks).call(SLEEP);
        }

        /** {@code if (top == 0) { body }} */
        public P ifZero(P body) {
            b.raw(0x45, 0x04, 0x40);
            merge(body);
            b.raw(0x0B);
            return this;
        }

        /** {@code f64.const} */
        public P f64(double v) {
            b.raw(0x44).f64(v);
            return this;
        }

        /** {@code if (top == value) { body }} */
        public P ifEq(int value, P body) {
            i32(value).raw(0x46, 0x04, 0x40);
            merge(body);
            b.raw(0x0B);
            return this;
        }

        /** {@code if (top == value) { body }} for an i64 on the stack. */
        public P ifEqI64(long value, P body) {
            i64(value).raw(0x51, 0x04, 0x40);
            merge(body);
            b.raw(0x0B);
            return this;
        }

        /** {@code if (top == value) { body }} for an f64 on the stack. */
        public P ifEqF64(double value, P body) {
            f64(value).raw(0x61, 0x04, 0x40);
            merge(body);
            b.raw(0x0B);
            return this;
        }

        /** Three consecutive {@code f64.const}s, for imports taking an (x, y, z) triple. */
        public P xyz(double x, double y, double z) {
            return f64(x).f64(y).f64(z);
        }

        /** Appends another sequence. */
        public P append(P other) {
            merge(other);
            return this;
        }

        /** Spawns a task running {@code body} with argument {@code 0}; drops the task id. */
        public P child(P body) {
            return childWithData(0, body).drop();
        }

        /**
         * Spawns a task running {@code body}, handing it {@code dataLocal}'s current value —
         * which the body reads as its own local 0. Drops the task id.
         */
        public P child(int dataLocal, P body) {
            return taskIndex(body).get(dataLocal).call(SPAWN).drop();
        }

        /** {@link #child(int, P)}, but stores the new task's id in {@code idLocal}. */
        public P childWithId(int idLocal, int dataLocal, P body) {
            return taskIndex(body).get(dataLocal).call(SPAWN).set(idLocal);
        }

        /** Spawns a task running {@code body} with the constant argument {@code data}. */
        public P childWithData(int data, P body) {
            return taskIndex(body).i32(data).call(SPAWN);
        }

        /**
         * A raw {@code spawn} with a literal table index — for the tests that hand the engine
         * an index no task was ever registered at.
         */
        public P spawnRaw(int entry, int data) {
            return i32(entry).i32(data).call(SPAWN);
        }

        /**
         * The two-call receive protocol: {@code channel_recv_len} into {@code lenLocal}, then
         * {@code channel_recv} into {@code buffer}, then log the bytes received.
         */
        public P recvAndLog(int channelLocal, int lenLocal, int buffer) {
            return get(channelLocal).call(CHANNEL_RECV_LEN).set(lenLocal)
                    .get(channelLocal).i32(buffer).call(CHANNEL_RECV)
                    .logBytes(buffer, lenLocal);
        }

        /** Sends {@code len} bytes of the letter table starting at letter {@code index}. */
        public P send(int channelLocal, int index, int len) {
            return get(channelLocal).i32(index).i32(len).call(CHANNEL_SEND);
        }

        /** Emits {@code i32.const <table index of task>}, patched when the module is built. */
        private P taskIndex(P task) {
            b.raw(0x41);
            fixes.add(new Fix(b.size(), task));
            b.raw(0x00); // placeholder: one byte, so patching never shifts anything
            return this;
        }

        /** Splices {@code other}'s bytes in, rebasing its pending patch offsets. */
        private void merge(P other) {
            int base = b.size();
            for (Fix f : other.fixes) {
                fixes.add(new Fix(f.offset() + base, f.task()));
            }
            b.buf(other.b);
        }

        /** This sequence's bytes with every task index patched in. */
        private byte[] resolved(IdentityHashMap<P, Integer> indices) {
            byte[] bytes = b.toBytes();
            for (Fix f : fixes) {
                bytes[f.offset()] = indices.get(f.task()).byteValue();
            }
            return bytes;
        }
    }

    /**
     * An embedder-owned import surface: the imports a plugin's host registers and the
     * handshake export its load check reads. The engine fixture stays vocabulary-free; the
     * embedder's own test tree declares its names here and keeps the index constants
     * {@link #imp} hands back. Registered types may duplicate engine signatures — the type
     * section tolerates duplicates, and locality beats sharing in a fixture.
     */
    public static final class Surface {

        private final String module;
        private final String abiExport;
        private final List<int[]> types = new ArrayList<>();
        private final List<String> names = new ArrayList<>();
        private final List<Integer> typeIndices = new ArrayList<>();

        /**
         * @param module the wasm import module the embedder owns (never the engine's)
         * @param abiExport the handshake export, e.g. {@code _billboard_abi}
         */
        public Surface(String module, String abiExport) {
            if (RuntimeWasm.ENGINE_MODULE.equals(module)) {
                throw new IllegalArgumentException("a Surface may not claim the engine module");
            }
            this.module = module;
            this.abiExport = abiExport;
        }

        /** Registers a raw function-type signature ({@code 0x60 …}); returns its handle. */
        public int type(int... rawSignature) {
            types.add(rawSignature.clone());
            return types.size() - 1;
        }

        /** Declares the next import with a {@link #type} handle; returns what `call` takes. */
        public int imp(String name, int type) {
            names.add(name);
            typeIndices.add(type);
            return ENGINE_IMPORT_COUNT + names.size() - 1;
        }

        public String abiExport() {
            return abiExport;
        }
    }

    /** Wraps {@code main} into an engine-only module reporting the current engine ABI. */
    public static byte[] module(P main) {
        return module(main, MachineInstance.ENGINE_ABI_VERSION);
    }

    /** Wraps {@code main} into an engine-only module reporting engine version {@code abiVersion}. */
    public static byte[] module(P main, int abiVersion) {
        return module(main, abiVersion, null, 0);
    }

    /** Wraps {@code main} into a module carrying {@code surface} beside the engine imports. */
    public static byte[] module(P main, Surface surface, int surfaceAbiVersion) {
        return module(main, MachineInstance.ENGINE_ABI_VERSION, surface, surfaceAbiVersion);
    }

    /** Wraps {@code main} into a module reporting each handshake version explicitly. */
    public static byte[] module(P main, int abiVersion, Surface surface, int surfaceAbiVersion) {
        List<P> tasks = collectTasks(main);
        IdentityHashMap<P, Integer> taskIndices = new IdentityHashMap<>();
        for (int i = 0; i < tasks.size(); i++) {
            taskIndices.put(tasks.get(i), i);
        }

        int surfaceTypes = surface == null ? 0 : surface.types.size();
        Buf types = new Buf().vec(ENGINE_TYPE_COUNT + surfaceTypes)
                .raw(0x60, 0x02, 0x7F, 0x7F, 0x00)       // 0 (i32,i32)->()
                .raw(0x60, 0x00, 0x01, 0x7F)             // 1 ()->(i32)
                .raw(0x60, 0x01, 0x7E, 0x00)             // 2 (i64)->()
                .raw(0x60, 0x00, 0x00)                   // 3 ()->()
                .raw(0x60, 0x01, 0x7F, 0x00)             // 4 (i32)->()  — also the task entry
                .raw(0x60, 0x01, 0x7F, 0x01, 0x7F)       // 5 (i32)->(i32)
                .raw(0x60, 0x02, 0x7F, 0x7F, 0x01, 0x7F) // 6 (i32,i32)->(i32)
                .raw(0x60, 0x03, 0x7F, 0x7F, 0x7F, 0x00) // 7 (i32,i32,i32)->()
                .raw(0x60, 0x00, 0x01, 0x7E)             // 8 ()->(i64)
                .raw(0x60, 0x04, 0x7F, 0x7F, 0x7F, 0x7F, 0x01, 0x7F) // 9 realloc
                .raw(0x60, 0x01, 0x7C, 0x01, 0x7C)             // 10 (f64)->(f64) — unary math
                .raw(0x60, 0x02, 0x7C, 0x7C, 0x01, 0x7C)       // 11 (f64,f64)->(f64) — pow, atan2
                // 12 (f64,i32,i32,i32)->(i32) — format_f64
                .raw(0x60, 0x04, 0x7C, 0x7F, 0x7F, 0x7F, 0x01, 0x7F);
        if (surface != null) {
            for (int[] signature : surface.types) {
                types.raw(signature);
            }
        }

        int surfaceImports = surface == null ? 0 : surface.names.size();
        int importCount = ENGINE_IMPORT_COUNT + surfaceImports;
        Buf imports = new Buf().vec(importCount);
        imp(imports, "log", 0);
        imp(imports, "spawn", 6);
        imp(imports, "sleep", 2);
        imp(imports, "exit", 3);
        imp(imports, "join", 4);
        imp(imports, "kill", 4);
        imp(imports, "signal_new", 1);
        imp(imports, "signal_notify", 0);
        imp(imports, "barrier_new", 5);
        imp(imports, "wait_all", 6);
        imp(imports, "wait_any", 6);
        imp(imports, "wait", 4);
        imp(imports, "channel_new", 5);
        imp(imports, "channel_send", 7);
        imp(imports, "channel_recv_len", 5);
        imp(imports, "channel_recv", 0);
        imp(imports, "channel_peek_len", 5);
        imp(imports, "channel_peek", 0);
        imp(imports, "channel_try_len", 5);
        imp(imports, "channel_clear", 4);
        imp(imports, "random_nondet", 8);
        imp(imports, "random_det", 8);
        imp(imports, "seed_random", 2);
        imp(imports, "fail", 0);
        imp(imports, "realloc", 9);
        imp(imports, "cbrt", 10);
        imp(imports, "pow", 11);
        imp(imports, "exp", 10);
        imp(imports, "ln", 10);
        imp(imports, "log10", 10);
        imp(imports, "sin", 10);
        imp(imports, "cos", 10);
        imp(imports, "tan", 10);
        imp(imports, "asin", 10);
        imp(imports, "acos", 10);
        imp(imports, "atan2", 11);
        imp(imports, "format_f64", 12);
        imp(imports, "environ_len", 1);
        imp(imports, "environ_read", 4);
        if (surface != null) {
            for (int i = 0; i < surface.names.size(); i++) {
                imports.name(surface.module).name(surface.names.get(i))
                        .raw(0x00).uleb(ENGINE_TYPE_COUNT + surface.typeIndices.get(i));
            }
        }

        // main and the engine handshake — plus the surface handshake — are all ()->(i32); the
        // task bodies that follow them are (i32)->().
        int fixedBodies = surface == null ? 2 : 3;
        Buf funcs = new Buf().vec(fixedBodies + tasks.size());
        for (int i = 0; i < fixedBodies; i++) {
            funcs.uleb(1);
        }
        for (int i = 0; i < tasks.size(); i++) {
            funcs.uleb(TASK_TYPE);
        }

        // One extra table slot past the registered tasks, deliberately left null, so a test can
        // spawn a null element without having to guess an index.
        Buf table = new Buf().vec(1).raw(0x70, 0x00).uleb(tasks.size() + 1);
        Buf memory = new Buf().vec(1).raw(0x00).uleb(1);
        Buf globals = RuntimeWasm.section(6, new Buf().vec(2)
                .raw(0x7F, 0x00).raw(0x41).sleb(1024).raw(0x0B)   // 0: __heap_base (immutable)
                .raw(0x7F, 0x01).raw(0x41).sleb(1024).raw(0x0B)); // 1: __stack_pointer (mutable)
        Buf exports = new Buf().vec(fixedBodies + 2)
                .name("_engine_main").raw(0x00).uleb(importCount)
                .name("_engine_abi").raw(0x00).uleb(importCount + 1);
        if (surface != null) {
            exports.name(surface.abiExport).raw(0x00).uleb(importCount + 2);
        }
        exports.name("__heap_base").raw(0x03).uleb(0);
        exports.name("__stack_pointer").raw(0x03).uleb(STACK_POINTER_GLOBAL);

        Buf mainLocals = new Buf().vec(2).uleb(8).raw(0x7F).uleb(2).raw(0x7E);
        // A task body's local 0 is its i32 parameter, so its scratch locals start at 1.
        Buf taskLocals = new Buf().vec(2).uleb(8).raw(0x7F).uleb(2).raw(0x7E);
        Buf mainBody = body(mainLocals,
                new Buf().bytes(main.resolved(taskIndices)).raw(0x41, 0x00, 0x0B));
        Buf abiBody = body(new Buf().vec(0), new Buf().raw(0x41).sleb(abiVersion).raw(0x0B));
        Buf code = new Buf().vec(fixedBodies + tasks.size()).buf(mainBody).buf(abiBody);
        if (surface != null) {
            code.buf(body(new Buf().vec(0),
                    new Buf().raw(0x41).sleb(surfaceAbiVersion).raw(0x0B)));
        }
        for (P task : tasks) {
            code.buf(body(taskLocals, new Buf().bytes(task.resolved(taskIndices)).raw(0x0B)));
        }

        byte[] letters = new byte[26];
        for (int i = 0; i < letters.length; i++) {
            letters[i] = (byte) ('A' + i);
        }
        Buf data = new Buf().vec(1).uleb(0).raw(0x41, 0x00, 0x0B).uleb(letters.length).bytes(letters);

        List<Buf> sections = new ArrayList<>(List.of(
                RuntimeWasm.section(1, types), RuntimeWasm.section(2, imports),
                RuntimeWasm.section(3, funcs), RuntimeWasm.section(4, table),
                RuntimeWasm.section(5, memory), globals, RuntimeWasm.section(7, exports)));
        if (!tasks.isEmpty()) {
            Buf elemBody = new Buf().vec(1).uleb(0).raw(0x41, 0x00, 0x0B).vec(tasks.size());
            for (int i = 0; i < tasks.size(); i++) {
                elemBody.uleb(importCount + fixedBodies + i);
            }
            sections.add(RuntimeWasm.section(9, elemBody));
        }
        sections.add(RuntimeWasm.section(10, code));
        sections.add(RuntimeWasm.section(11, data));
        return RuntimeWasm.module(sections.toArray(new Buf[0]));
    }

    /** Every task body reachable from {@code main}, in the order the table lists them. */
    private static List<P> collectTasks(P main) {
        List<P> ordered = new ArrayList<>();
        IdentityHashMap<P, Boolean> seen = new IdentityHashMap<>();
        Deque<P> pending = new ArrayDeque<>();
        pending.add(main);
        while (!pending.isEmpty()) {
            for (P.Fix fix : pending.remove().fixes) {
                if (seen.put(fix.task(), Boolean.TRUE) == null) {
                    ordered.add(fix.task());
                    pending.add(fix.task());
                }
            }
        }
        if (ordered.size() > MAX_TASKS) {
            throw new IllegalArgumentException(
                    "a fixture may declare at most " + MAX_TASKS + " task bodies");
        }
        return ordered;
    }

    private static void imp(Buf imports, String name, int typeIndex) {
        imports.name(RuntimeWasm.ENGINE_MODULE).name(name).raw(0x00).uleb(typeIndex);
    }

    private static Buf body(Buf localsDecl, Buf instructions) {
        byte[] bytes = new Buf().buf(localsDecl).buf(instructions).toBytes();
        return new Buf().uleb(bytes.length).bytes(bytes);
    }
}
