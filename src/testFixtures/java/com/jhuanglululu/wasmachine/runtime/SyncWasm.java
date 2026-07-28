package com.jhuanglululu.wasmachine.runtime;

import com.jhuanglululu.wasm.HostFunction;
import com.jhuanglululu.wasmachine.runtime.RuntimeWasm.Buf;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-rolled WebAssembly modules for the engine imports — tasks, sync, random and the math
 * kernel — plus the plugin-owned entity and effect names an embedder supplies. Like
 * {@link RuntimeWasm} it writes section framing by hand, so the tests exercise the real
 * interpreter and the real host-import path rather than a stand-in for them.
 *
 * <p>Every module has the same shape: one import table (indices are the {@code *} constants
 * below), a 1-page memory whose first 26 bytes are {@code "ABC…Z"} (so {@code log(i, 1)}
 * prints the {@code i}-th letter and {@code channel_send(ch, i, 1)} sends it), scratch space
 * from byte 64 up, and a {@code _engine_main} body supplied by the test through {@link P}.
 * {@code _engine_abi} returns {@link MachineInstance#ENGINE_ABI_VERSION} and the plugin
 * handshake {@value #PLUGIN_ABI_EXPORT} returns {@value #PLUGIN_ABI_VERSION}.
 *
 * <p>Imports are declared against two modules, as ABI 3 requires: the engine's own names under
 * {@value RuntimeWasm#ENGINE_MODULE} and the plugin-owned entity/effect names under
 * {@value RuntimeWasm#PLUGIN_MODULE}.
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
    // ABI v2 entities, then the v1 entity imports the kind-dispatch tests need.
    public static final int SPAWN_ITEM_DISPLAY = 24;
    public static final int SPAWN_TEXT_DISPLAY = 25;
    public static final int SPAWN_ARMOR_STAND = 26;
    public static final int SPAWN_ITEM = 27;
    public static final int SET_ITEM = 28;
    public static final int GET_ITEM_LEN = 29;
    public static final int GET_ITEM = 30;
    public static final int SET_DISPLAY_CONTEXT = 31;
    public static final int GET_DISPLAY_CONTEXT = 32;
    public static final int SET_BILLBOARD_MODE = 33;
    public static final int GET_BILLBOARD_MODE = 34;
    public static final int SET_TEXT = 35;
    public static final int GET_TEXT_LEN = 36;
    public static final int GET_TEXT = 37;
    public static final int SET_TEXT_BACKGROUND = 38;
    public static final int GET_TEXT_BACKGROUND = 39;
    public static final int SET_TEXT_OPACITY = 40;
    public static final int GET_TEXT_OPACITY = 41;
    public static final int SET_LINE_WIDTH = 42;
    public static final int GET_LINE_WIDTH = 43;
    public static final int SET_TEXT_FLAGS = 44;
    public static final int GET_TEXT_FLAGS = 45;
    public static final int SET_POSE = 46;
    public static final int GET_POSE = 47;
    public static final int SET_EQUIPMENT = 48;
    public static final int SET_STAND_FLAGS = 49;
    public static final int GET_STAND_FLAGS = 50;
    public static final int SET_YAW = 51;
    public static final int GET_YAW = 52;
    public static final int PLAY_SOUND = 53;
    public static final int EMIT_PARTICLE = 54;
    public static final int EMIT_PARTICLE_DUST = 55;
    public static final int EMIT_PARTICLE_DUST_TRANSITION = 56;
    public static final int EMIT_PARTICLE_BLOCK = 57;
    public static final int EMIT_PARTICLE_ITEM = 58;
    public static final int SPAWN_BLOCK_DISPLAY = 59;
    public static final int SET_BLOCK = 60;
    public static final int SET_POSITION = 61;
    public static final int SET_ROTATION = 62;
    public static final int SET_SCALE = 63;
    public static final int DESPAWN = 64;
    public static final int GET_POSITION = 65;
    public static final int GET_BLOCK_LEN = 66;
    public static final int GET_BLOCK = 67;
    public static final int REALLOC = 68;
    // The engine math kernel (guest-abi.md); appended last so every index above is unchanged.
    public static final int CBRT = 69;
    public static final int POW = 70;
    public static final int EXP = 71;
    public static final int LN = 72;
    public static final int LOG10 = 73;
    public static final int SIN = 74;
    public static final int COS = 75;
    public static final int TAN = 76;
    public static final int ASIN = 77;
    public static final int ACOS = 78;
    public static final int ATAN2 = 79;
    public static final int FORMAT_F64 = 80;

    private static final int IMPORT_COUNT = 81;

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

        /** The three origin-relative f64 coordinates every spawner and effect import ends with. */
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
     * The plugin handshake version these modules report. The fixture stands in for a whole
     * embedder — it already declares the plugin's imports — so it exports the plugin's
     * handshake beside the engine's, which is what an embedder's own load check reads.
     */
    public static final int PLUGIN_ABI_VERSION = 3;

    /** The plugin handshake export, named for the embedder these fixtures stand in for. */
    public static final String PLUGIN_ABI_EXPORT = "_billboard_abi";

    /** Wraps {@code main} into a complete module reporting both current ABI versions. */
    public static byte[] module(P main) {
        return module(main, MachineInstance.ENGINE_ABI_VERSION);
    }

    /** Wraps {@code main} into a module reporting engine version {@code abiVersion}. */
    public static byte[] module(P main, int abiVersion) {
        return module(main, abiVersion, PLUGIN_ABI_VERSION);
    }

    /** Wraps {@code main} into a module reporting each handshake version explicitly. */
    public static byte[] module(P main, int abiVersion, int pluginAbiVersion) {
        Buf types = new Buf().vec(27)
                .raw(0x60, 0x02, 0x7F, 0x7F, 0x00)       // 0 (i32,i32)->()
                .raw(0x60, 0x00, 0x01, 0x7F)             // 1 ()->(i32)
                .raw(0x60, 0x01, 0x7E, 0x00)             // 2 (i64)->()
                .raw(0x60, 0x00, 0x00)                   // 3 ()->()
                .raw(0x60, 0x01, 0x7F, 0x00)             // 4 (i32)->()
                .raw(0x60, 0x01, 0x7F, 0x01, 0x7F)       // 5 (i32)->(i32)
                .raw(0x60, 0x02, 0x7F, 0x7F, 0x01, 0x7F) // 6 (i32,i32)->(i32)
                .raw(0x60, 0x03, 0x7F, 0x7F, 0x7F, 0x00) // 7 (i32,i32,i32)->()
                .raw(0x60, 0x00, 0x01, 0x7E)             // 8 ()->(i64)
                // 9 (i32,i32,f64,f64,f64)->(i32) — the string+position spawners
                .raw(0x60, 0x05, 0x7F, 0x7F, 0x7C, 0x7C, 0x7C, 0x01, 0x7F)
                // 10 (f64,f64,f64)->(i32) — spawn_armor_stand
                .raw(0x60, 0x03, 0x7C, 0x7C, 0x7C, 0x01, 0x7F)
                .raw(0x60, 0x02, 0x7F, 0x7E, 0x00)       // 11 (i32,i64)->()
                .raw(0x60, 0x01, 0x7F, 0x01, 0x7E)       // 12 (i32)->(i64)
                // 13 (i32,i32,f64,f64,f64,i64)->() — set_pose
                .raw(0x60, 0x06, 0x7F, 0x7F, 0x7C, 0x7C, 0x7C, 0x7E, 0x00)
                .raw(0x60, 0x04, 0x7F, 0x7F, 0x7F, 0x7F, 0x00) // 14 set_equipment
                .raw(0x60, 0x03, 0x7F, 0x7C, 0x7E, 0x00) // 15 (i32,f64,i64)->() set_yaw
                .raw(0x60, 0x01, 0x7F, 0x01, 0x7C)       // 16 (i32)->(f64) get_yaw
                // 17 play_sound (ptr,len: i32, x,y,z: f64, category: i32, volume,pitch: f64)
                .raw(0x60, 0x08, 0x7F, 0x7F, 0x7C, 0x7C, 0x7C, 0x7F, 0x7C, 0x7C, 0x00)
                // 18 emit_particle / _block / _item (ptr,len, x,y,z, count, ox,oy,oz, speed)
                .raw(0x60, 0x0A, 0x7F, 0x7F, 0x7C, 0x7C, 0x7C, 0x7F, 0x7C, 0x7C, 0x7C, 0x7C, 0x00)
                // 19 emit_particle_dust (r,g,b,size, x,y,z: f64, count: i32, ox,oy,oz,speed: f64)
                .raw(0x60, 0x0C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7F,
                        0x7C, 0x7C, 0x7C, 0x7C, 0x00)
                // 20 emit_particle_dust_transition (6 colours + size + x,y,z, count, ox,oy,oz,speed)
                .raw(0x60, 0x0F, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C,
                        0x7F, 0x7C, 0x7C, 0x7C, 0x7C, 0x00)
                // 21 (i32,f64,f64,f64,i64)->() — set_position, set_scale
                .raw(0x60, 0x05, 0x7F, 0x7C, 0x7C, 0x7C, 0x7E, 0x00)
                // 22 (i32,f64,f64,f64,f64,i64)->() — set_rotation
                .raw(0x60, 0x06, 0x7F, 0x7C, 0x7C, 0x7C, 0x7C, 0x7E, 0x00)
                // 23 (i32,i32,i32,i32)->(i32) — realloc
                .raw(0x60, 0x04, 0x7F, 0x7F, 0x7F, 0x7F, 0x01, 0x7F)
                .raw(0x60, 0x01, 0x7C, 0x01, 0x7C)             // 24 (f64)->(f64) — unary math
                .raw(0x60, 0x02, 0x7C, 0x7C, 0x01, 0x7C)       // 25 (f64,f64)->(f64) — pow, atan2
                // 26 (f64,i32,i32,i32)->(i32) — format_f64
                .raw(0x60, 0x04, 0x7C, 0x7F, 0x7F, 0x7F, 0x01, 0x7F);

        Buf imports = new Buf().vec(IMPORT_COUNT);
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
        imp(imports, "spawn_item_display", 9);
        imp(imports, "spawn_text_display", 9);
        imp(imports, "spawn_armor_stand", 10);
        imp(imports, "spawn_item", 9);
        imp(imports, "set_item", 7);
        imp(imports, "get_item_len", 5);
        imp(imports, "get_item", 0);
        imp(imports, "set_display_context", 0);
        imp(imports, "get_display_context", 5);
        imp(imports, "set_billboard_mode", 0);
        imp(imports, "get_billboard_mode", 5);
        imp(imports, "set_text", 7);
        imp(imports, "get_text_len", 5);
        imp(imports, "get_text", 0);
        imp(imports, "set_text_background", 11);
        imp(imports, "get_text_background", 12);
        imp(imports, "set_text_opacity", 11);
        imp(imports, "get_text_opacity", 12);
        imp(imports, "set_line_width", 11);
        imp(imports, "get_line_width", 12);
        imp(imports, "set_text_flags", 0);
        imp(imports, "get_text_flags", 5);
        imp(imports, "set_pose", 13);
        imp(imports, "get_pose", 7);
        imp(imports, "set_equipment", 14);
        imp(imports, "set_stand_flags", 0);
        imp(imports, "get_stand_flags", 5);
        imp(imports, "set_yaw", 15);
        imp(imports, "get_yaw", 16);
        imp(imports, "play_sound", 17);
        imp(imports, "emit_particle", 18);
        imp(imports, "emit_particle_dust", 19);
        imp(imports, "emit_particle_dust_transition", 20);
        imp(imports, "emit_particle_block", 18);
        imp(imports, "emit_particle_item", 18);
        imp(imports, "spawn_block_display", 9);
        imp(imports, "set_block", 7);
        imp(imports, "set_position", 21);
        imp(imports, "set_rotation", 22);
        imp(imports, "set_scale", 21);
        imp(imports, "despawn", 4);
        imp(imports, "get_position", 0);
        imp(imports, "get_block_len", 5);
        imp(imports, "get_block", 0);
        imp(imports, "realloc", 23);
        imp(imports, "cbrt", 24);
        imp(imports, "pow", 25);
        imp(imports, "exp", 24);
        imp(imports, "ln", 24);
        imp(imports, "log10", 24);
        imp(imports, "sin", 24);
        imp(imports, "cos", 24);
        imp(imports, "tan", 24);
        imp(imports, "asin", 24);
        imp(imports, "acos", 24);
        imp(imports, "atan2", 25);
        imp(imports, "format_f64", 26);

        // main, the engine handshake and the plugin handshake — all ()->(i32).
        Buf funcs = new Buf().vec(3).uleb(1).uleb(1).uleb(1);
        Buf memory = new Buf().vec(1).raw(0x00).uleb(1);
        Buf globals = RuntimeWasm.section(6,
                new Buf().vec(1).raw(0x7F, 0x00).raw(0x41).sleb(1024).raw(0x0B));
        Buf exports = new Buf().vec(4)
                .name("_engine_main").raw(0x00).uleb(IMPORT_COUNT)
                .name("_engine_abi").raw(0x00).uleb(IMPORT_COUNT + 1)
                .name(PLUGIN_ABI_EXPORT).raw(0x00).uleb(IMPORT_COUNT + 2)
                .name("__heap_base").raw(0x03).uleb(0);
        Buf locals = new Buf().vec(2).uleb(8).raw(0x7F).uleb(2).raw(0x7E);
        Buf mainBody = body(locals, new Buf().buf(main.b).raw(0x41, 0x00, 0x0B));
        Buf abiBody = body(new Buf().vec(0), new Buf().raw(0x41).sleb(abiVersion).raw(0x0B));
        Buf pluginAbiBody = body(new Buf().vec(0),
                new Buf().raw(0x41).sleb(pluginAbiVersion).raw(0x0B));
        Buf code = new Buf().vec(3).buf(mainBody).buf(abiBody).buf(pluginAbiBody);

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

    /**
     * Emits one import, routed to its owner's module: ABI 3 splits the namespaces, so the
     * declared module name is not a property of the emitter but of who implements the name.
     */
    private static void imp(Buf imports, String name, int typeIndex) {
        String module = PLUGIN_IMPORTS.contains(name)
                ? RuntimeWasm.PLUGIN_MODULE
                : RuntimeWasm.ENGINE_MODULE;
        imports.name(module).name(name).raw(0x00).uleb(typeIndex);
    }

    /**
     * The imports above that the <em>engine</em> does not own — Billboard's entity and effect
     * calls. They are emitted unconditionally (the import indices are constants the tests
     * address by), so an engine-only run still has to bind something to them; see
     * {@link #stubPluginImports()}. Drift is self-detecting: a name missing from this list
     * fails instantiation with "missing host import".
     */
    private static final List<String> PLUGIN_IMPORTS = List.of(
            "spawn_item_display", "spawn_text_display", "spawn_armor_stand", "spawn_item",
            "set_item", "get_item_len", "get_item", "set_display_context", "get_display_context",
            "set_billboard_mode", "get_billboard_mode", "set_text", "get_text_len", "get_text",
            "set_text_background", "get_text_background", "set_text_opacity", "get_text_opacity",
            "set_line_width", "get_line_width", "set_text_flags", "get_text_flags",
            "set_pose", "get_pose", "set_equipment", "set_stand_flags", "get_stand_flags",
            "set_yaw", "get_yaw", "play_sound", "emit_particle", "emit_particle_dust",
            "emit_particle_dust_transition", "emit_particle_block", "emit_particle_item",
            "spawn_block_display", "set_block", "set_position", "set_rotation", "set_scale",
            "despawn", "get_position", "get_block_len", "get_block");

    /**
     * No-op bindings for every plugin-owned import, so an engine test can instantiate these
     * modules without an embedder. Each returns {@code 0}; engine tests never call them.
     */
    public static Map<String, Map<String, HostFunction>> stubPluginImports() {
        Map<String, HostFunction> functions = new HashMap<>();
        for (String name : PLUGIN_IMPORTS) {
            functions.put(name, (ctx, args) -> 0L);
        }
        return Map.of(RuntimeWasm.PLUGIN_MODULE, functions);
    }

    private static Buf body(Buf localsDecl, Buf instructions) {
        byte[] bytes = new Buf().buf(localsDecl).buf(instructions).toBytes();
        return new Buf().uleb(bytes.length).bytes(bytes);
    }
}
