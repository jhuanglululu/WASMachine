package com.jhuanglululu.wasmachine.runtime;

import com.jhuanglululu.wasm.Buf;
import com.jhuanglululu.wasm.ExecutionContext;
import com.jhuanglululu.wasm.Instance;
import com.jhuanglululu.wasm.Module;
import java.util.Map;

/**
 * Hand-rolls the small WebAssembly modules the runtime tests need (LEB and section
 * framing written by hand, independent of the parser). Each module exports a
 * {@code __heap_base} global so {@link MachineInstance} can start, and their handshake
 * export {@code _engine_abi} returns {@link MachineInstance#ENGINE_ABI_VERSION}.
 */
public final class RuntimeWasm {

    private RuntimeWasm() {}

    /**
     * The engine's own import module. ABI 3 split the namespaces (see guest-abi.md) so the
     * boundary is structural: the engine owns this one, a plugin owns its own.
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

    /** A global section with a single exported {@code __heap_base = base} (i32). */
    private static Buf heapBaseGlobal(int base) {
        return section(6, new Buf().vec(1).raw(0x7F, 0x00).raw(0x41).sleb(base).raw(0x0B));
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
        Buf exports = new Buf().vec(3)
                .name("_engine_main").raw(0x00).uleb(1) // func 1
                .name("_engine_abi").raw(0x00).uleb(2)  // func 2
                .name("__heap_base").raw(0x03).uleb(0);    // global 0
        Buf mainBody = new Buf().raw(0x41, 0x00, 0x41, 0x04, 0x10, 0x00, 0x41, 0x00, 0x0B);
        Buf abiBody = new Buf().raw(0x41).sleb(MachineInstance.ENGINE_ABI_VERSION).raw(0x0B);
        Buf code = new Buf().vec(2).buf(codeBody(mainBody)).buf(codeBody(abiBody));
        Buf data = new Buf().vec(1).uleb(0).raw(0x41, 0x00, 0x0B).uleb(4).raw(0x62, 0x6F, 0x6F, 0x6D); // "boom"
        return module(section(1, types), section(2, imports), section(3, funcs),
                section(5, memory), heapBaseGlobal(1024), section(7, exports),
                section(10, code), section(11, data));
    }

    /** A module exporting {@code _engine_main} but NOT {@code _engine_abi}. */
    public static byte[] missingAbiModule() {
        Buf types = new Buf().vec(1).raw(0x60, 0x00, 0x01, 0x7F); // ()->(i32)
        Buf funcs = new Buf().vec(1).uleb(0);
        Buf exports = new Buf().vec(2)
                .name("_engine_main").raw(0x00).uleb(0)
                .name("__heap_base").raw(0x03).uleb(0);
        Buf code = new Buf().vec(1).buf(codeBody(new Buf().raw(0x41, 0x00, 0x0B)));
        return module(section(1, types), section(3, funcs), heapBaseGlobal(1024),
                section(7, exports), section(10, code));
    }

    /** A module whose {@code main} spins forever; {@code _engine_abi} is the engine version. */
    public static byte[] infiniteLoopModule() {
        Buf types = new Buf().vec(1).raw(0x60, 0x00, 0x01, 0x7F); // ()->(i32)
        Buf funcs = new Buf().vec(2).uleb(0).uleb(0);
        Buf exports = new Buf().vec(3)
                .name("_engine_main").raw(0x00).uleb(0)
                .name("_engine_abi").raw(0x00).uleb(1)
                .name("__heap_base").raw(0x03).uleb(0);
        Buf mainBody = new Buf().raw(0x03, 0x40, 0x0C, 0x00, 0x0B, 0x41, 0x00, 0x0B); // loop { br 0 } (unreachable const)
        Buf abiBody = new Buf().raw(0x41).sleb(MachineInstance.ENGINE_ABI_VERSION).raw(0x0B);
        Buf code = new Buf().vec(2).buf(codeBody(mainBody)).buf(codeBody(abiBody));
        return module(section(1, types), section(3, funcs), heapBaseGlobal(1024),
                section(7, exports), section(10, code));
    }

    /**
     * A determinism probe: {@code main} forks a child; both sleep 1 tick, then the parent
     * logs "P" and the child logs "C". Woken the same tick, spawn order makes P precede C.
     */
    public static byte[] forkDeterminismModule() {
        Buf types = new Buf().vec(5)
                .raw(0x60, 0x02, 0x7F, 0x7F, 0x00) // type0 log (i32,i32)->()
                .raw(0x60, 0x00, 0x01, 0x7F)       // type1 ()->(i32)  (fork, main, abi)
                .raw(0x60, 0x01, 0x7E, 0x00)       // type2 sleep (i64)->()
                .raw(0x60, 0x00, 0x00)             // type3 exit ()->()
                .raw(0x60, 0x01, 0x7F, 0x00);      // type4 join (i32)->()
        Buf imports = new Buf().vec(5)
                .name(ENGINE_MODULE).name("log").raw(0x00).uleb(0)
                .name(ENGINE_MODULE).name("fork").raw(0x00).uleb(1)
                .name(ENGINE_MODULE).name("sleep").raw(0x00).uleb(2)
                .name(ENGINE_MODULE).name("exit").raw(0x00).uleb(3)
                .name(ENGINE_MODULE).name("join").raw(0x00).uleb(4);
        Buf funcs = new Buf().vec(2).uleb(1).uleb(1); // main type1, abi type1 (func 5, 6)
        Buf memory = new Buf().vec(1).raw(0x00).uleb(1);
        Buf exports = new Buf().vec(3)
                .name("_engine_main").raw(0x00).uleb(5)
                .name("_engine_abi").raw(0x00).uleb(6)
                .name("__heap_base").raw(0x03).uleb(0);
        Buf mainInstr = new Buf()
                .raw(0x10, 0x01)             // call fork
                .raw(0x22, 0x00)             // local.tee 0 (childId)
                .raw(0x45)                   // i32.eqz
                .raw(0x04, 0x40)             // if (child branch)
                .raw(0x42, 0x01, 0x10, 0x02) // sleep(1)
                .raw(0x41, 0x01, 0x41, 0x01, 0x10, 0x00) // log(ptr=1,len=1) "C"
                .raw(0x10, 0x03)             // exit
                .raw(0x0B)                   // end if
                .raw(0x42, 0x01, 0x10, 0x02) // sleep(1)  (parent)
                .raw(0x41, 0x00, 0x41, 0x01, 0x10, 0x00) // log(ptr=0,len=1) "P"
                .raw(0x20, 0x00, 0x10, 0x04) // join(childId)
                .raw(0x41, 0x00);            // i32.const 0 (return)
        Buf mainBody = codeBodyWithLocals(new Buf().vec(1).uleb(1).raw(0x7F),
                new Buf().buf(mainInstr).raw(0x0B));
        Buf abiBody = codeBody(new Buf().raw(0x41).sleb(MachineInstance.ENGINE_ABI_VERSION)
                .raw(0x0B));
        Buf code = new Buf().vec(2).buf(mainBody).buf(abiBody);
        Buf data = new Buf().vec(1).uleb(0).raw(0x41, 0x00, 0x0B).uleb(2).raw(0x50, 0x43); // "PC"
        return module(section(1, types), section(2, imports), section(3, funcs),
                section(5, memory), heapBaseGlobal(1024), section(7, exports),
                section(10, code), section(11, data));
    }
}
