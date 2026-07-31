package com.jhuanglululu.wasmachine.runtime;

import com.jhuanglululu.wasm.Buf;
import java.util.ArrayList;
import java.util.List;

/**
 * Hand-rolled WebAssembly modules for the engine imports — tasks, sync, random and the math
 * kernel. Like {@link RuntimeWasm} it writes section framing by hand, so the tests exercise
 * the real interpreter and the real host-import path rather than a stand-in for them.
 *
 * <p>Every module has the same shape: one import table (indices are the {@code *} constants
 * below), a 1-page memory whose first 26 bytes are {@code "ABC…Z"} (so {@code log(i, 1)}
 * prints the {@code i}-th letter and {@code channel_send(ch, i, 1)} sends it), scratch space
 * from byte 64 up, and a {@code _engine_main} body supplied by the test through {@link P}.
 * {@code _engine_abi} returns {@link MachineInstance#ENGINE_ABI_VERSION}.
 *
 * <p>The engine does not know any embedder's vocabulary — ABI 3 made that boundary
 * structural. An embedder whose own imports are under test appends them through a
 * {@link Surface}: its imports land after the engine's in the one import table, declared
 * against the embedder's module, and its handshake export is emitted beside the engine's.
 */
public final class SyncWasm {

    private SyncWasm() {}

    // Import indices, in declaration order (what `call` takes).
    public static final int LOG = 0;
    public static final int FORK = 1;
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

    /** Where a {@link Surface}'s imports start: the engine's own end at this index. */
    public static final int ENGINE_IMPORT_COUNT = 37;

    private static final int ENGINE_TYPE_COUNT = 13;

    /** Scratch address for received channel payloads (well clear of the letter table). */
    public static final int SCRATCH = 64;

    /**
     * An instruction-sequence builder. Locals 0..7 are {@code i32} and 8..9 are {@code i64};
     * the body's trailing {@code i32.const 0} + {@code end} is added by {@link #module}.
     */
    public static final class P {

        private final Buf b = new Buf();

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
            b.raw(0x45, 0x04, 0x40).buf(body.b).raw(0x0B);
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
            b.buf(body.b).raw(0x0B);
            return this;
        }

        /** {@code if (top == value) { body }} for an i64 on the stack. */
        public P ifEqI64(long value, P body) {
            i64(value).raw(0x51, 0x04, 0x40);
            b.buf(body.b).raw(0x0B);
            return this;
        }

        /** {@code if (top == value) { body }} for an f64 on the stack. */
        public P ifEqF64(double value, P body) {
            f64(value).raw(0x61, 0x04, 0x40);
            b.buf(body.b).raw(0x0B);
            return this;
        }

        /** Three consecutive {@code f64.const}s, for imports taking an (x, y, z) triple. */
        public P xyz(double x, double y, double z) {
            return f64(x).f64(y).f64(z);
        }

        /** Appends another sequence. */
        public P append(P other) {
            b.buf(other.b);
            return this;
        }

        /** Forks a child that runs {@code body} then exits; the parent falls through. */
        public P child(P body) {
            return call(FORK).ifZero(new P().append(body).call(EXIT));
        }

        /** Like {@link #child} but also stores the child's task id in {@code idLocal}. */
        public P childWithId(int idLocal, P body) {
            return call(FORK).tee(idLocal).ifZero(new P().append(body).call(EXIT));
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
        int surfaceTypes = surface == null ? 0 : surface.types.size();
        Buf types = new Buf().vec(ENGINE_TYPE_COUNT + surfaceTypes)
                .raw(0x60, 0x02, 0x7F, 0x7F, 0x00)       // 0 (i32,i32)->()
                .raw(0x60, 0x00, 0x01, 0x7F)             // 1 ()->(i32)
                .raw(0x60, 0x01, 0x7E, 0x00)             // 2 (i64)->()
                .raw(0x60, 0x00, 0x00)                   // 3 ()->()
                .raw(0x60, 0x01, 0x7F, 0x00)             // 4 (i32)->()
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
        imp(imports, "fork", 1);
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
        if (surface != null) {
            for (int i = 0; i < surface.names.size(); i++) {
                imports.name(surface.module).name(surface.names.get(i))
                        .raw(0x00).uleb(ENGINE_TYPE_COUNT + surface.typeIndices.get(i));
            }
        }

        // main and the engine handshake — plus the surface handshake — all ()->(i32).
        int bodyCount = surface == null ? 2 : 3;
        Buf funcs = new Buf().vec(bodyCount);
        for (int i = 0; i < bodyCount; i++) {
            funcs.uleb(1);
        }
        Buf memory = new Buf().vec(1).raw(0x00).uleb(1);
        Buf globals = RuntimeWasm.section(6,
                new Buf().vec(1).raw(0x7F, 0x00).raw(0x41).sleb(1024).raw(0x0B));
        Buf exports = new Buf().vec(bodyCount + 1)
                .name("_engine_main").raw(0x00).uleb(importCount)
                .name("_engine_abi").raw(0x00).uleb(importCount + 1);
        if (surface != null) {
            exports.name(surface.abiExport).raw(0x00).uleb(importCount + 2);
        }
        exports.name("__heap_base").raw(0x03).uleb(0);
        Buf locals = new Buf().vec(2).uleb(8).raw(0x7F).uleb(2).raw(0x7E);
        Buf mainBody = body(locals, new Buf().buf(main.b).raw(0x41, 0x00, 0x0B));
        Buf abiBody = body(new Buf().vec(0), new Buf().raw(0x41).sleb(abiVersion).raw(0x0B));
        Buf code = new Buf().vec(bodyCount).buf(mainBody).buf(abiBody);
        if (surface != null) {
            code.buf(body(new Buf().vec(0),
                    new Buf().raw(0x41).sleb(surfaceAbiVersion).raw(0x0B)));
        }

        byte[] letters = new byte[26];
        for (int i = 0; i < letters.length; i++) {
            letters[i] = (byte) ('A' + i);
        }
        Buf data = new Buf().vec(1).uleb(0).raw(0x41, 0x00, 0x0B).uleb(letters.length).bytes(letters);

        return RuntimeWasm.module(RuntimeWasm.section(1, types), RuntimeWasm.section(2, imports),
                RuntimeWasm.section(3, funcs), RuntimeWasm.section(5, memory), globals,
                RuntimeWasm.section(7, exports), RuntimeWasm.section(10, code),
                RuntimeWasm.section(11, data));
    }

    private static void imp(Buf imports, String name, int typeIndex) {
        imports.name(RuntimeWasm.ENGINE_MODULE).name(name).raw(0x00).uleb(typeIndex);
    }

    private static Buf body(Buf localsDecl, Buf instructions) {
        byte[] bytes = new Buf().buf(localsDecl).buf(instructions).toBytes();
        return new Buf().uleb(bytes.length).bytes(bytes);
    }
}
