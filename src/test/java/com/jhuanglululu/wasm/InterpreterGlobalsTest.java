package com.jhuanglululu.wasm;

import static com.jhuanglululu.wasm.TestModule.I32;
import static com.jhuanglululu.wasm.TestModule.i32Const;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jhuanglululu.wasm.WasmBuilder.Buf;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Globals: init-expression evaluation, get, and set of a mutable global. */
class InterpreterGlobalsTest {

    private static int run(Buf globals, Buf body) {
        byte[] bytes = new TestModule()
                .types(new Buf().vec(1).raw(0x60, 0x00, 0x01, I32))
                .globals(globals)
                .body(body)
                .build();
        Instance inst = new Instance(Module.parse(bytes), Map.of());
        ExecResult r = inst.invoke(inst.instantiate(), "main", new long[0], 1_000_000);
        return (int) ((ExecResult.Completed) r).values()[0];
    }

    private static Buf twoGlobals() {
        // g0: mutable i32 = 10 ; g1: immutable i32 = 20
        return new Buf().vec(2)
                .raw(0x7F, 0x01).buf(i32Const(10)).raw(0x0B)
                .raw(0x7F, 0x00).buf(i32Const(20)).raw(0x0B);
    }

    @Test
    void globalGetReadsInitializedValues() {
        // global.get 0 + global.get 1 = 30
        Buf body = new Buf().raw(0x23, 0x00).raw(0x23, 0x01).raw(0x6A);
        assertEquals(30, run(twoGlobals(), body));
    }

    @Test
    void globalSetThenGet() {
        // g0 = 99 ; return g0
        Buf body = new Buf().buf(i32Const(99)).raw(0x24, 0x00).raw(0x23, 0x00);
        assertEquals(99, run(twoGlobals(), body));
    }

    @Test
    void globalInitFromI64Const() {
        Buf globals = new Buf().vec(1).raw(0x7E, 0x00).raw(0x42).bytes(WasmBuilder.sleb(1234567890123L)).raw(0x0B);
        // return (i32.wrap_i64 (global.get 0))
        Buf body = new Buf().raw(0x23, 0x00).raw(0xA7);
        assertEquals((int) 1234567890123L, run(globals, body));
    }
}
