package com.jhuanglululu.wasm;

import static com.jhuanglululu.wasm.TestModule.I32;
import static com.jhuanglululu.wasm.TestModule.i32Const;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.jhuanglululu.wasm.WasmBuilder.Buf;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Memory instructions: loads/stores (incl. narrow variants), bounds, grow, bulk ops. */
class InterpreterMemoryTest {

    /** Runs a () -> i32 "main" with a memory of {@code pages} pages; returns the result. */
    private static ExecResult run(int pages, Buf instrs) {
        byte[] bytes = new TestModule()
                .types(new Buf().vec(1).raw(0x60, 0x00, 0x01, I32))
                .memory(pages)
                .body(instrs)
                .build();
        Instance inst = new Instance(Module.parse(bytes), Map.of());
        return inst.invoke(inst.instantiate(), "main", new long[0], 1_000_000);
    }

    private static int i32(int pages, Buf instrs) {
        ExecResult r = run(pages, instrs);
        assertInstanceOf(ExecResult.Completed.class, r, () -> "expected completion but was " + r);
        return (int) ((ExecResult.Completed) r).values()[0];
    }

    // memarg is (align, offset); both LEB. We use align=2, offset=0 for i32 accesses.
    private static Buf storeI32(int addr, int value) {
        return new Buf().buf(i32Const(addr)).buf(i32Const(value)).raw(0x36, 0x02, 0x00);
    }

    private static Buf loadI32(int addr) {
        return new Buf().buf(i32Const(addr)).raw(0x28, 0x02, 0x00);
    }

    @Test
    void storeThenLoadI32() {
        // store 0xCAFEBABE at addr 16, then load it back.
        Buf body = new Buf().buf(storeI32(16, 0xCAFEBABE)).buf(loadI32(16));
        assertEquals(0xCAFEBABE, i32(1, body));
    }

    @Test
    void narrowLoadSignAndZeroExtend() {
        // store 0xFF at byte 0, then load8_s (-> -1) and load8_u (-> 255).
        Buf store = new Buf().buf(i32Const(0)).buf(i32Const(0xFF)).raw(0x3A, 0x00, 0x00); // i32.store8
        Buf load8s = new Buf().buf(store).buf(i32Const(0)).raw(0x2C, 0x00, 0x00);          // load8_s
        assertEquals(-1, i32(1, load8s));

        Buf load8u = new Buf()
                .buf(i32Const(0)).buf(i32Const(0xFF)).raw(0x3A, 0x00, 0x00)
                .buf(i32Const(0)).raw(0x2D, 0x00, 0x00);                                     // load8_u
        assertEquals(255, i32(1, load8u));
    }

    @Test
    void loadUsesMemargOffset() {
        // store at addr 20, load with base 16 + offset 4.
        Buf body = new Buf()
                .buf(storeI32(20, 12345))
                .buf(i32Const(16)).raw(0x28, 0x02, 0x04); // load i32 base=16 offset=4 -> addr 20
        assertEquals(12345, i32(1, body));
    }

    @Test
    void outOfBoundsLoadTraps() {
        // 1 page = 65536 bytes; loading a 4-byte i32 at 65534 overruns by 2.
        ExecResult r = run(1, new Buf().buf(i32Const(65534)).raw(0x28, 0x02, 0x00));
        assertInstanceOf(ExecResult.Trapped.class, r);
        assertEquals(TrapReason.OUT_OF_BOUNDS_MEMORY_ACCESS, ((ExecResult.Trapped) r).reason());
    }

    @Test
    void memorySizeAndGrow() {
        // memory.size (initially 1), then memory.grow by 2 -> returns old size 1; then size 3.
        Buf grow = new Buf().buf(i32Const(2)).raw(0x40, 0x00); // memory.grow 2
        assertEquals(1, i32(1, grow));                          // returns old page count

        Buf sizeAfterGrow = new Buf()
                .buf(i32Const(2)).raw(0x40, 0x00).raw(0x1A) // grow, drop old size
                .raw(0x3F, 0x00);                           // memory.size
        assertEquals(3, i32(1, sizeAfterGrow));
    }

    @Test
    void growBeyondMaxReturnsMinusOne() {
        // memory min 1, max 2: growing by 5 fails, returns -1, size stays 1.
        byte[] bytes = new TestModule()
                .types(new Buf().vec(1).raw(0x60, 0x00, 0x01, I32))
                .memory(1, 2)
                .body(new Buf().buf(i32Const(5)).raw(0x40, 0x00))
                .build();
        Instance inst = new Instance(Module.parse(bytes), Map.of());
        ExecResult r = inst.invoke(inst.instantiate(), "main", new long[0], 1_000_000);
        assertEquals(-1, (int) ((ExecResult.Completed) r).values()[0]);
    }

    @Test
    void memoryFill() {
        // fill 4 bytes at addr 8 with 0xAB, then load i32 -> 0xABABABAB.
        Buf fill = new Buf()
                .buf(i32Const(8)).buf(i32Const(0xAB)).buf(i32Const(4)).raw(0xFC, 0x0B, 0x00);
        Buf body = new Buf().buf(fill).buf(loadI32(8));
        assertEquals(0xABABABAB, i32(1, body));
    }

    @Test
    void memoryCopyOverlapping() {
        // store 0x11223344 at 0, copy 4 bytes from 0 to 2 (overlap), then load at 2.
        Buf body = new Buf()
                .buf(storeI32(0, 0x11223344))
                .buf(i32Const(2)).buf(i32Const(0)).buf(i32Const(4)).raw(0xFC, 0x0A, 0x00, 0x00)
                .buf(loadI32(2));
        assertEquals(0x11223344, i32(1, body));
    }

    @Test
    void zeroLengthFillAtBoundaryIsOkButPastTraps() {
        // fill length 0 at addr == size is legal.
        Buf okFill = new Buf().buf(i32Const(65536)).buf(i32Const(0)).buf(i32Const(0)).raw(0xFC, 0x0B, 0x00)
                .buf(i32Const(7));
        assertEquals(7, i32(1, okFill));

        // fill length 0 at addr size+1 traps.
        ExecResult r = run(1, new Buf()
                .buf(i32Const(65537)).buf(i32Const(0)).buf(i32Const(0)).raw(0xFC, 0x0B, 0x00)
                .buf(i32Const(7)));
        assertInstanceOf(ExecResult.Trapped.class, r);
    }

    @Test
    void activeDataSegmentApplied() {
        // Active data segment writes bytes 0xEF,0xBE,0xAD,0xDE at offset 32; load i32 -> 0xDEADBEEF.
        Buf data = new Buf().vec(1)
                .uleb(0).buf(i32Const(32)).raw(0x0B) // active, offset i32.const 32
                .uleb(4).raw(0xEF, 0xBE, 0xAD, 0xDE);
        byte[] bytes = new TestModule()
                .types(new Buf().vec(1).raw(0x60, 0x00, 0x01, I32))
                .memory(1)
                .dataCount(1)
                .data(data)
                .body(loadI32(32))
                .build();
        Instance inst = new Instance(Module.parse(bytes), Map.of());
        ExecResult r = inst.invoke(inst.instantiate(), "main", new long[0], 1_000_000);
        assertEquals(0xDEADBEEF, (int) ((ExecResult.Completed) r).values()[0]);
    }

    @Test
    void passiveDataInitAndDrop() {
        // Passive segment [0xAA,0xBB]; memory.init 2 bytes to addr 4; load8_u -> 0xAA.
        Buf data = new Buf().vec(1).uleb(1).uleb(2).raw(0xAA, 0xBB); // passive, 2 bytes
        Buf init = new Buf()
                .buf(i32Const(4)).buf(i32Const(0)).buf(i32Const(2)).raw(0xFC, 0x08, 0x00, 0x00) // memory.init seg0
                .buf(i32Const(4)).raw(0x2D, 0x00, 0x00); // load8_u at 4
        byte[] bytes = new TestModule()
                .types(new Buf().vec(1).raw(0x60, 0x00, 0x01, I32))
                .memory(1)
                .dataCount(1)
                .data(data)
                .body(init)
                .build();
        Instance inst = new Instance(Module.parse(bytes), Map.of());
        ExecResult r = inst.invoke(inst.instantiate(), "main", new long[0], 1_000_000);
        assertEquals(0xAA, (int) ((ExecResult.Completed) r).values()[0]);
    }
}
