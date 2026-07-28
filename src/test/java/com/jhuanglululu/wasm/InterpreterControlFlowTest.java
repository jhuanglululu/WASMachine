package com.jhuanglululu.wasm;

import static com.jhuanglululu.wasm.TestModule.I32;
import static com.jhuanglululu.wasm.TestModule.i32;
import static com.jhuanglululu.wasm.TestModule.i32Const;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.jhuanglululu.wasm.WasmBuilder.Buf;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Control flow: blocks/loops/if-else via the sidetable, br_table dispatch, calls. */
class InterpreterControlFlowTest {

    /** Runs a (i32) -> (i32) "main" with the given argument. */
    private static ExecResult runParam(Buf locals, Buf instrs, int arg) {
        byte[] bytes = new TestModule()
                .types(new Buf().vec(1).raw(0x60, 0x01, I32, 0x01, I32))
                .locals(locals)
                .body(instrs)
                .build();
        Instance inst = new Instance(Module.parse(bytes), Map.of());
        return inst.invoke(inst.instantiate(), "main", new long[] {arg}, 1_000_000);
    }

    private static int i32Param(Buf locals, Buf instrs, int arg) {
        ExecResult r = runParam(locals, instrs, arg);
        assertInstanceOf(ExecResult.Completed.class, r, () -> "expected completion but was " + r);
        return (int) ((ExecResult.Completed) r).values()[0];
    }

    private static Buf noLocals() {
        return new Buf().vec(0);
    }

    @Test
    void blockBranchSkipsDeadCode() {
        // (block (result i32) i32.const 1  br 0  i32.const 999)  -> 1
        Buf body = new Buf()
                .raw(0x02, I32)          // block (result i32)
                .buf(i32Const(1))
                .raw(0x0C, 0x00)         // br 0
                .buf(i32Const(999))      // dead
                .raw(0x0B);              // end block
        assertEquals(1, i32(body));
    }

    @Test
    void loopComputesSum() {
        // sum 1..5 = 15, using a countdown loop with br_if back-edge.
        Buf locals = new Buf().vec(1).uleb(2).raw(I32); // 2 i32 locals: [i, acc]
        Buf body = new Buf()
                .buf(i32Const(5)).raw(0x21, 0x00)   // i = 5
                .buf(i32Const(0)).raw(0x21, 0x01)   // acc = 0
                .raw(0x03, 0x40)                    // loop
                .raw(0x20, 0x01).raw(0x20, 0x00).raw(0x6A).raw(0x21, 0x01) // acc += i
                .raw(0x20, 0x00).buf(i32Const(1)).raw(0x6B).raw(0x21, 0x00) // i -= 1
                .raw(0x20, 0x00).raw(0x0D, 0x00)    // br_if 0 (loop while i != 0)
                .raw(0x0B)                          // end loop
                .raw(0x20, 0x01);                   // push acc
        // main is (i32)->(i32) but ignores its arg; pass 0.
        assertEquals(15, i32Param(locals, body, 0));
    }

    @Test
    void ifElseSelectsBranch() {
        // if (arg) 10 else 20
        Buf body = new Buf()
                .raw(0x20, 0x00)          // local.get 0 (condition)
                .raw(0x04, I32)           // if (result i32)
                .buf(i32Const(10))
                .raw(0x05)                // else
                .buf(i32Const(20))
                .raw(0x0B);               // end
        assertEquals(10, i32Param(noLocals(), body, 1));
        assertEquals(20, i32Param(noLocals(), body, 0));
    }

    @Test
    void brTableDispatch() {
        // switch(selector): 0 -> 100, 1 -> 200, default -> 300.
        Buf body = new Buf()
                .raw(0x02, I32)   // block $done (result i32)
                .raw(0x02, 0x40)  //   block $a
                .raw(0x02, 0x40)  //     block $b
                .raw(0x02, 0x40)  //       block $c
                .raw(0x20, 0x00)  //         local.get 0 (selector)
                .raw(0x0E, 0x02, 0x00, 0x01, 0x02) // br_table 0 1 (default 2)
                .raw(0x0B)        //       end $c   (selector 0 lands here)
                .buf(i32Const(100)).raw(0x0C, 0x02) // br $done
                .raw(0x0B)        //     end $b     (selector 1 lands here)
                .buf(i32Const(200)).raw(0x0C, 0x01) // br $done
                .raw(0x0B)        //   end $a       (default lands here)
                .buf(i32Const(300))
                .raw(0x0B);       // end $done
        assertEquals(100, i32Param(noLocals(), body, 0));
        assertEquals(200, i32Param(noLocals(), body, 1));
        assertEquals(300, i32Param(noLocals(), body, 2));   // default
        assertEquals(300, i32Param(noLocals(), body, 99));  // clamps to default
    }

    @Test
    void directCall() {
        // helper(x) = x + 1; main() = helper(41) = 42.
        Buf helper = new Buf().vec(0).raw(0x20, 0x00).buf(i32Const(1)).raw(0x6A, 0x0B);
        Buf main = new Buf().vec(0).buf(i32Const(41)).raw(0x10, 0x00, 0x0B); // call func 0
        byte[] bytes = new WasmBuilder()
                .section(1, new Buf().vec(2)
                        .raw(0x60, 0x01, I32, 0x01, I32)   // type0 (i32)->(i32)
                        .raw(0x60, 0x00, 0x01, I32))       // type1 ()->(i32)
                .section(3, new Buf().vec(2).uleb(0).uleb(1))
                .section(7, new Buf().vec(1).name("main").raw(0x00).uleb(1))
                .section(10, new Buf().vec(2)
                        .uleb(helper.toBytes().length).buf(helper)
                        .uleb(main.toBytes().length).buf(main))
                .build();
        Instance inst = new Instance(Module.parse(bytes), Map.of());
        ExecResult r = inst.invoke(inst.instantiate(), "main", new long[0], 1_000_000);
        assertEquals(42, (int) ((ExecResult.Completed) r).values()[0]);
    }

    // --- call_indirect: one target returning 7, called through a table ---

    private static byte[] callIndirectModule(int callTypeIndex, int tableIndexOperand, boolean withElem) {
        WasmBuilder b = new WasmBuilder()
                .section(1, new Buf().vec(2)
                        .raw(0x60, 0x00, 0x01, I32)          // type0 ()->(i32)
                        .raw(0x60, 0x01, I32, 0x01, I32))    // type1 (i32)->(i32)
                .section(3, new Buf().vec(2).uleb(0).uleb(0)) // func0, func1 both type0
                .section(4, new Buf().vec(1).raw(0x70, 0x00, 0x01)); // table funcref min 1
        b.section(7, new Buf().vec(1).name("main").raw(0x00).uleb(1));
        if (withElem) {
            b.section(9, new Buf().vec(1).uleb(0).buf(i32Const(0)).raw(0x0B).vec(1).uleb(0)); // elem[0]=func0
        }
        Buf target = new Buf().vec(0).buf(i32Const(7)).raw(0x0B);
        // main: (optional i32 arg for a mismatched type) then table index, call_indirect.
        Buf main = new Buf().vec(0);
        if (callTypeIndex == 1) {
            main.buf(i32Const(5)); // an argument for type1 (i32)->(i32)
        }
        main.buf(i32Const(tableIndexOperand)).raw(0x11).uleb(callTypeIndex).uleb(0).raw(0x0B);
        b.section(10, new Buf().vec(2)
                .uleb(target.toBytes().length).buf(target)
                .uleb(main.toBytes().length).buf(main));
        return b.build();
    }

    private static ExecResult runIndirect(byte[] bytes) {
        Instance inst = new Instance(Module.parse(bytes), Map.of());
        return inst.invoke(inst.instantiate(), "main", new long[0], 1_000_000);
    }

    @Test
    void callIndirectSucceeds() {
        ExecResult r = runIndirect(callIndirectModule(0, 0, true));
        assertEquals(7, (int) ((ExecResult.Completed) r).values()[0]);
    }

    @Test
    void callIndirectTypeMismatchTraps() {
        // call_indirect declares type1 but table[0] is type0.
        ExecResult r = runIndirect(callIndirectModule(1, 0, true));
        assertInstanceOf(ExecResult.Trapped.class, r);
        assertEquals(TrapReason.INDIRECT_CALL_TYPE_MISMATCH, ((ExecResult.Trapped) r).reason());
    }

    @Test
    void callIndirectNullElementTraps() {
        // No elem segment: table[0] stays null.
        ExecResult r = runIndirect(callIndirectModule(0, 0, false));
        assertInstanceOf(ExecResult.Trapped.class, r);
        assertEquals(TrapReason.UNINITIALIZED_ELEMENT, ((ExecResult.Trapped) r).reason());
    }

    @Test
    void callIndirectOutOfBoundsTraps() {
        // Table size 1; index 5 is out of range.
        ExecResult r = runIndirect(callIndirectModule(0, 5, true));
        assertInstanceOf(ExecResult.Trapped.class, r);
        assertEquals(TrapReason.UNDEFINED_ELEMENT, ((ExecResult.Trapped) r).reason());
    }
}
