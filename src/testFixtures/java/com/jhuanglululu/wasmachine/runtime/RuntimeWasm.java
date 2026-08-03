package com.jhuanglululu.wasmachine.runtime;

import com.jhuanglululu.wasm.Buf;
import com.jhuanglululu.wasm.ExecutionContext;
import com.jhuanglululu.wasm.Instance;
import com.jhuanglululu.wasm.Module;
import java.util.Map;

/**
 * Hand-rolls the small WebAssembly modules the runtime tests need (LEB and section
 * framing written by hand, independent of the parser). Each module exports the two globals
 * {@link MachineInstance} requires to start — {@code __heap_base} and the mutable
 * {@code __stack_pointer} of engine ABI 2 — and their handshake export {@code _engine_abi}
 * returns {@link MachineInstance#ENGINE_ABI_VERSION}.
 */
public final class RuntimeWasm {

    private RuntimeWasm() {}

    /**
     * The engine's own import module. The namespace split (see guest-abi.md) made the
     * boundary structural: the engine owns this one, a plugin owns its own.
     */
    public static final String ENGINE_MODULE = "engine";

    public static Buf section(int id, Buf body) {
        byte[] b = body.toBytes();
        return new Buf().u8(id).uleb(b.length).bytes(b);
    }

    public static byte[] module(Buf... sections) {
        Buf m = new Buf().raw(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00);
        for (Buf s : sections) {
            m.buf(s);
        }
        return m.toBytes();
    }

    /**
     * The global section every runtime fixture uses: global 0 is the immutable
     * {@code __heap_base}, global 1 the mutable {@code __stack_pointer} engine ABI 2 requires
     * (its initial value only matters to a guest that really uses a shadow stack).
     */
    private static Buf globals(int heapBase) {
        return section(6, new Buf().vec(2)
                .raw(0x7F, 0x00).raw(0x41).sleb(heapBase).raw(0x0B)
                .raw(0x7F, 0x01).raw(0x41).sleb(heapBase).raw(0x0B));
    }

    /** Appends the two global exports every fixture module carries. */
    private static Buf globalExports(Buf exports) {
        return exports.name("__heap_base").raw(0x03).uleb(0)
                .name("__stack_pointer").raw(0x03).uleb(1);
    }

    private static Buf codeBody(Buf instructions) {
        byte[] body = new Buf().vec(0).buf(instructions).toBytes(); // locals count 0
        return new Buf().uleb(body.length).bytes(body);
    }

    private static Buf codeBodyWithLocals(Buf localsDecl, Buf instructions) {
        byte[] body = new Buf().buf(localsDecl).buf(instructions).toBytes();
        return new Buf().uleb(body.length).bytes(body);
    }

    // --- concrete modules ---

    /** A module with only a linear memory of {@code pages} pages. */
    public static ExecutionContext memoryContext(int pages) {
        byte[] bytes = module(section(5, new Buf().vec(1).raw(0x00).uleb(pages)));
        return new Instance(Module.parse(bytes), Map.of()).instantiate();
    }

    /**
     * A module whose {@code main} calls {@code engine.fail("boom")}; {@code _engine_abi}
     * returns the engine ABI version.
     */
    public static byte[] failModule() {
        Buf types = new Buf().vec(2)
                .raw(0x60, 0x02, 0x7F, 0x7F, 0x00)  // type0 (i32,i32)->()
                .raw(0x60, 0x00, 0x01, 0x7F);        // type1 ()->(i32)
        Buf imports = new Buf().vec(1).name(ENGINE_MODULE).name("fail").raw(0x00).uleb(0);
        Buf funcs = new Buf().vec(2).uleb(1).uleb(1); // main type1, abi type1
        Buf memory = new Buf().vec(1).raw(0x00).uleb(1);
        Buf exports = globalExports(new Buf().vec(4)
                .name("_engine_main").raw(0x00).uleb(1) // func 1
                .name("_engine_abi").raw(0x00).uleb(2)); // func 2
        Buf mainBody = new Buf().raw(0x41, 0x00, 0x41, 0x04, 0x10, 0x00, 0x41, 0x00, 0x0B);
        Buf abiBody = new Buf().raw(0x41).sleb(MachineInstance.ENGINE_ABI_VERSION).raw(0x0B);
        Buf code = new Buf().vec(2).buf(codeBody(mainBody)).buf(codeBody(abiBody));
        Buf data = new Buf().vec(1).uleb(0).raw(0x41, 0x00, 0x0B).uleb(4).raw(0x62, 0x6F, 0x6F, 0x6D); // "boom"
        return module(section(1, types), section(2, imports), section(3, funcs),
                section(5, memory), globals(1024), section(7, exports),
                section(10, code), section(11, data));
    }

    /** A module exporting {@code _engine_main} but NOT {@code _engine_abi}. */
    public static byte[] missingAbiModule() {
        Buf types = new Buf().vec(1).raw(0x60, 0x00, 0x01, 0x7F); // ()->(i32)
        Buf funcs = new Buf().vec(1).uleb(0);
        Buf exports = globalExports(new Buf().vec(3)
                .name("_engine_main").raw(0x00).uleb(0));
        Buf code = new Buf().vec(1).buf(codeBody(new Buf().raw(0x41, 0x00, 0x0B)));
        return module(section(1, types), section(3, funcs), globals(1024),
                section(7, exports), section(10, code));
    }

    /**
     * A module that exports everything an instance needs <em>except</em> the mutable
     * {@code __stack_pointer} engine ABI 2 requires.
     */
    public static byte[] missingStackPointerModule() {
        Buf types = new Buf().vec(1).raw(0x60, 0x00, 0x01, 0x7F); // ()->(i32)
        Buf funcs = new Buf().vec(2).uleb(0).uleb(0);
        Buf exports = new Buf().vec(3)
                .name("_engine_main").raw(0x00).uleb(0)
                .name("_engine_abi").raw(0x00).uleb(1)
                .name("__heap_base").raw(0x03).uleb(0);
        Buf abiBody = new Buf().raw(0x41).sleb(MachineInstance.ENGINE_ABI_VERSION).raw(0x0B);
        Buf code = new Buf().vec(2).buf(codeBody(new Buf().raw(0x41, 0x00, 0x0B)))
                .buf(codeBody(abiBody));
        return module(section(1, types), section(3, funcs), globals(1024),
                section(7, exports), section(10, code));
    }

    /** A module that exports {@code __stack_pointer}, but as an <em>immutable</em> global. */
    public static byte[] immutableStackPointerModule() {
        Buf types = new Buf().vec(1).raw(0x60, 0x00, 0x01, 0x7F); // ()->(i32)
        Buf funcs = new Buf().vec(2).uleb(0).uleb(0);
        Buf immutableGlobals = section(6, new Buf().vec(2)
                .raw(0x7F, 0x00).raw(0x41).sleb(1024).raw(0x0B)
                .raw(0x7F, 0x00).raw(0x41).sleb(1024).raw(0x0B));
        Buf exports = globalExports(new Buf().vec(4)
                .name("_engine_main").raw(0x00).uleb(0)
                .name("_engine_abi").raw(0x00).uleb(1));
        Buf abiBody = new Buf().raw(0x41).sleb(MachineInstance.ENGINE_ABI_VERSION).raw(0x0B);
        Buf code = new Buf().vec(2).buf(codeBody(new Buf().raw(0x41, 0x00, 0x0B)))
                .buf(codeBody(abiBody));
        return module(section(1, types), section(3, funcs), immutableGlobals,
                section(7, exports), section(10, code));
    }

    /** A module whose {@code main} spins forever; {@code _engine_abi} is the engine version. */
    public static byte[] infiniteLoopModule() {
        Buf types = new Buf().vec(1).raw(0x60, 0x00, 0x01, 0x7F); // ()->(i32)
        Buf funcs = new Buf().vec(2).uleb(0).uleb(0);
        Buf exports = globalExports(new Buf().vec(4)
                .name("_engine_main").raw(0x00).uleb(0)
                .name("_engine_abi").raw(0x00).uleb(1));
        Buf mainBody = new Buf().raw(0x03, 0x40, 0x0C, 0x00, 0x0B, 0x41, 0x00, 0x0B); // loop { br 0 } (unreachable const)
        Buf abiBody = new Buf().raw(0x41).sleb(MachineInstance.ENGINE_ABI_VERSION).raw(0x0B);
        Buf code = new Buf().vec(2).buf(codeBody(mainBody)).buf(codeBody(abiBody));
        return module(section(1, types), section(3, funcs), globals(1024),
                section(7, exports), section(10, code));
    }

    /**
     * A module whose {@code main} does {@code spawn(0, 0)} against a function table whose one
     * entry is {@code elemFunction} — so a test can point a task entry at something that is not
     * an {@code fn(i32)} and watch the engine refuse it. Function 0 is the {@code spawn} import
     * itself, 1 is {@code main} and 2 is {@code _engine_abi} (both {@code () -> i32}).
     */
    public static byte[] spawnEntryModule(int elemFunction) {
        Buf types = new Buf().vec(2)
                .raw(0x60, 0x00, 0x01, 0x7F)              // type0 ()->(i32)
                .raw(0x60, 0x02, 0x7F, 0x7F, 0x01, 0x7F); // type1 spawn (i32,i32)->(i32)
        Buf imports = new Buf().vec(1)
                .name(ENGINE_MODULE).name("spawn").raw(0x00).uleb(1);
        Buf funcs = new Buf().vec(2).uleb(0).uleb(0); // func 1 = main, func 2 = abi
        Buf table = new Buf().vec(1).raw(0x70, 0x00).uleb(1);
        Buf memory = new Buf().vec(1).raw(0x00).uleb(1);
        Buf exports = globalExports(new Buf().vec(4)
                .name("_engine_main").raw(0x00).uleb(1)
                .name("_engine_abi").raw(0x00).uleb(2));
        Buf elements = new Buf().vec(1).uleb(0).raw(0x41, 0x00, 0x0B).vec(1).uleb(elemFunction);
        Buf mainBody = codeBody(new Buf()
                .raw(0x41, 0x00, 0x41, 0x00, 0x10, 0x00, 0x1A) // spawn(0, 0), drop
                .raw(0x41, 0x00, 0x0B));
        Buf abiBody = codeBody(new Buf().raw(0x41).sleb(MachineInstance.ENGINE_ABI_VERSION)
                .raw(0x0B));
        Buf code = new Buf().vec(2).buf(mainBody).buf(abiBody);
        return module(section(1, types), section(2, imports), section(3, funcs),
                section(4, table), section(5, memory), globals(1024), section(7, exports),
                section(9, elements), section(10, code));
    }

    /**
     * A determinism probe: {@code main} spawns a child through the function table; both sleep
     * 1 tick, then the parent logs "P" and the child logs "C". Woken the same tick, spawn
     * order makes P precede C.
     */
    public static byte[] spawnDeterminismModule() {
        Buf types = new Buf().vec(6)
                .raw(0x60, 0x02, 0x7F, 0x7F, 0x00)        // type0 log (i32,i32)->()
                .raw(0x60, 0x00, 0x01, 0x7F)              // type1 ()->(i32)  (main, abi)
                .raw(0x60, 0x01, 0x7E, 0x00)              // type2 sleep (i64)->()
                .raw(0x60, 0x00, 0x00)                    // type3 exit ()->()
                .raw(0x60, 0x01, 0x7F, 0x00)              // type4 join / the task entry
                .raw(0x60, 0x02, 0x7F, 0x7F, 0x01, 0x7F); // type5 spawn (i32,i32)->(i32)
        Buf imports = new Buf().vec(5)
                .name(ENGINE_MODULE).name("log").raw(0x00).uleb(0)
                .name(ENGINE_MODULE).name("spawn").raw(0x00).uleb(5)
                .name(ENGINE_MODULE).name("sleep").raw(0x00).uleb(2)
                .name(ENGINE_MODULE).name("exit").raw(0x00).uleb(3)
                .name(ENGINE_MODULE).name("join").raw(0x00).uleb(4);
        // func 5 = main (type1), func 6 = abi (type1), func 7 = the child body (type4).
        Buf funcs = new Buf().vec(3).uleb(1).uleb(1).uleb(4);
        Buf table = new Buf().vec(1).raw(0x70, 0x00).uleb(1);
        Buf memory = new Buf().vec(1).raw(0x00).uleb(1);
        Buf exports = globalExports(new Buf().vec(4)
                .name("_engine_main").raw(0x00).uleb(5)
                .name("_engine_abi").raw(0x00).uleb(6));
        Buf elements = new Buf().vec(1).uleb(0).raw(0x41, 0x00, 0x0B).vec(1).uleb(7);
        Buf mainInstr = new Buf()
                .raw(0x41, 0x00)             // i32.const 0 — table index of the child body
                .raw(0x41, 0x00)             // i32.const 0 — the data argument
                .raw(0x10, 0x01)             // call spawn
                .raw(0x21, 0x00)             // local.set 0 (childId)
                .raw(0x42, 0x01, 0x10, 0x02) // sleep(1)
                .raw(0x41, 0x00, 0x41, 0x01, 0x10, 0x00) // log(ptr=0,len=1) "P"
                .raw(0x20, 0x00, 0x10, 0x04) // join(childId)
                .raw(0x41, 0x00);            // i32.const 0 (return)
        Buf mainBody = codeBodyWithLocals(new Buf().vec(1).uleb(1).raw(0x7F),
                new Buf().buf(mainInstr).raw(0x0B));
        Buf abiBody = codeBody(new Buf().raw(0x41).sleb(MachineInstance.ENGINE_ABI_VERSION)
                .raw(0x0B));
        Buf childBody = codeBody(new Buf()
                .raw(0x42, 0x01, 0x10, 0x02)             // sleep(1)
                .raw(0x41, 0x01, 0x41, 0x01, 0x10, 0x00) // log(ptr=1,len=1) "C"
                .raw(0x0B));
        Buf code = new Buf().vec(3).buf(mainBody).buf(abiBody).buf(childBody);
        Buf data = new Buf().vec(1).uleb(0).raw(0x41, 0x00, 0x0B).uleb(2).raw(0x50, 0x43); // "PC"
        return module(section(1, types), section(2, imports), section(3, funcs),
                section(4, table), section(5, memory), globals(1024), section(7, exports),
                section(9, elements), section(10, code), section(11, data));
    }
}
