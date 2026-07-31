package com.jhuanglululu.wasm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the five review findings (1 critical + 4 major). Each fails
 * against the pre-fix code (with a raw exception, wrong value, or missing validation)
 * and passes after the fix.
 */
class ReviewRegressionTest {

    // ---------- CRITICAL 1: branch to the function label from a CALLED frame ----------

    /** Builds a module where main() calls a helper then adds 1; returns main's result. */
    private static int callHelperPlusOne(Buf helperBodyWithEnd) {
        Buf helperBody = new Buf().vec(0).buf(helperBodyWithEnd);           // locals 0
        Buf mainBody = new Buf().vec(0).raw(0x10, 0x00, 0x41, 0x01, 0x6A, 0x0B); // call 0; i32.const 1; add; end
        byte[] bytes = new WasmBuilder()
                .section(1, new Buf().vec(1).raw(0x60, 0x00, 0x01, 0x7F))   // type0 () -> i32
                .section(3, new Buf().vec(2).uleb(0).uleb(0))                // func0 helper, func1 main
                .section(7, new Buf().vec(1).name("main").raw(0x00).uleb(1))
                .section(10, new Buf().vec(2)
                        .uleb(helperBody.toBytes().length).buf(helperBody)
                        .uleb(mainBody.toBytes().length).buf(mainBody))
                .build();
        Instance inst = new Instance(Module.parse(bytes), Map.of());
        ExecResult r = inst.invoke(inst.instantiate(), "main", new long[0], 100_000);
        return (int) ((ExecResult.Completed) r).values()[0];
    }

    @Test
    void brToFunctionLabelFromCalledFrameReturnsToCaller() {
        // helper: i32.const 5; br 0  (br to the function label = early return of 5)
        assertEquals(6, callHelperPlusOne(new Buf().raw(0x41, 0x05, 0x0C, 0x00, 0x0B)));
    }

    @Test
    void brIfToFunctionLabelFromCalledFrameReturnsToCaller() {
        // helper: i32.const 5; i32.const 1; br_if 0  (taken -> return 5)
        assertEquals(6, callHelperPlusOne(new Buf().raw(0x41, 0x05, 0x41, 0x01, 0x0D, 0x00, 0x0B)));
    }

    @Test
    void brTableToFunctionLabelFromCalledFrameReturnsToCaller() {
        // helper: i32.const 5; i32.const 0; br_table 0 (default 0) -> return 5
        assertEquals(6, callHelperPlusOne(new Buf().raw(0x41, 0x05, 0x41, 0x00, 0x0E, 0x00, 0x00, 0x0B)));
    }

    // ---------- MAJOR 2: unsigned u32-immediate bounds in the code scanner ----------

    private static byte[] bodyModule(Buf bodyWithoutEnd) {
        return new TestModule().body(bodyWithoutEnd).build(); // TestModule appends `end`
    }

    private static final byte[] U32_MAX = WasmBuilder.uleb(0xFFFFFFFFL);

    @Test
    void brWithBit31LabelIsRejectedNotNpe() {
        byte[] bytes = bodyModule(new Buf().raw(0x0C).bytes(U32_MAX)); // br 0xFFFFFFFF
        assertThrows(WasmParseException.class, () -> Module.parse(bytes));
    }

    @Test
    void brTableWithBit31CountIsRejectedNotNegativeArray() {
        // count 0x80000000 -> pre-fix `new int[n + 1]` threw NegativeArraySizeException.
        byte[] bytes = bodyModule(new Buf().raw(0x0E).bytes(WasmBuilder.uleb(0x80000000L)));
        assertThrows(WasmParseException.class, () -> Module.parse(bytes));
    }

    @Test
    void callWithBit31IndexIsRejected() {
        byte[] bytes = bodyModule(new Buf().raw(0x10).bytes(U32_MAX)); // call 0xFFFFFFFF
        assertThrows(WasmParseException.class, () -> Module.parse(bytes));
    }

    @Test
    void localGetWithBit31IndexIsRejected() {
        byte[] bytes = bodyModule(new Buf().raw(0x20).bytes(U32_MAX)); // local.get 0xFFFFFFFF
        assertThrows(WasmParseException.class, () -> Module.parse(bytes));
    }

    @Test
    void globalGetWithBit31IndexIsRejected() {
        byte[] bytes = bodyModule(new Buf().raw(0x23).bytes(U32_MAX)); // global.get 0xFFFFFFFF
        assertThrows(WasmParseException.class, () -> Module.parse(bytes));
    }

    // ---------- MAJOR 3: memory/table minimum bounds ----------

    @Test
    void oversizedMemoryMinimumIsRejectedAtParse() {
        // 50000 pages * 65536 would overflow int at instantiate; reject at parse.
        byte[] bytes = new TestModule().memory(50_000).build();
        assertThrows(WasmParseException.class, () -> Module.parse(bytes));
    }

    @Test
    void oversizedTableMinimumIsRejectedAtParse() {
        Buf tableSection = new Buf().vec(1).raw(0x70, 0x00).bytes(WasmBuilder.uleb(1L << 30)); // funcref, min 2^30
        byte[] bytes = new TestModule().tables(tableSection).build();
        assertThrows(WasmParseException.class, () -> Module.parse(bytes));
    }

    @Test
    void tableGrowByHugeDeltaReturnsMinusOne() {
        // table (funcref, min 1); main: ref.null func; i32.const 2^30; table.grow 0
        byte[] bytes = new TestModule()
                .types(new Buf().vec(1).raw(0x60, 0x00, 0x01, 0x7F)) // () -> i32
                .tables(new Buf().vec(1).raw(0x70, 0x00, 0x01))
                .body(new Buf().raw(0xD0, 0x70)
                        .raw(0x41).bytes(WasmBuilder.sleb(1 << 30))
                        .raw(0xFC, 0x0F, 0x00))
                .build();
        Instance inst = new Instance(Module.parse(bytes), Map.of());
        ExecResult r = inst.invoke(inst.instantiate(), "main", new long[0], 100_000);
        assertEquals(-1, (int) ((ExecResult.Completed) r).values()[0]);
    }

    // ---------- MAJOR 4: i32.trunc_f64_s boundary constants (exclusive both ends) ----------

    private static int i32(Buf instrs) {
        return (int) TestModule.completed(TestModule.exec(TestModule.I32, instrs))[0];
    }

    private static TrapReason trapReason(Buf instrs) {
        ExecResult r = TestModule.exec(TestModule.I32, instrs);
        assertInstanceOf(ExecResult.Trapped.class, r, () -> "expected trap but was " + r);
        return ((ExecResult.Trapped) r).reason();
    }

    @Test
    void truncF64SignedAcceptsRepresentableInRangeValues() {
        // 2147483647.5 truncates toward zero to 2147483647 (pre-fix wrongly trapped it).
        assertEquals(2147483647, i32(new Buf().buf(TestModule.f64Const(2147483647.5)).raw(0xAA)));
        // -2147483648.9 truncates to -2147483648 (MIN), still in range.
        assertEquals(-2147483648, i32(new Buf().buf(TestModule.f64Const(-2147483648.9)).raw(0xAA)));
    }

    @Test
    void truncF64SignedTrapsAtExclusiveBoundaries() {
        // 2147483648.0 (= 2^31) is out of range.
        assertEquals(TrapReason.INTEGER_OVERFLOW,
                trapReason(new Buf().buf(TestModule.f64Const(2147483648.0)).raw(0xAA)));
        // -2147483649.0 (= -2^31 - 1) is out of range (pre-fix wrongly accepted it).
        assertEquals(TrapReason.INTEGER_OVERFLOW,
                trapReason(new Buf().buf(TestModule.f64Const(-2147483649.0)).raw(0xAA)));
    }

    // ---------- MAJOR 5: fuel proportional to bulk-op length, resume-safe ----------

    /** A () -> () module whose main fills {@code len} bytes at 0 with 0xAB. */
    private static byte[] fillModule(int len) {
        return new TestModule()
                .types(new Buf().vec(1).raw(0x60, 0x00, 0x00)) // () -> ()
                .memory(1)
                .body(new Buf()
                        .buf(TestModule.i32Const(0))       // dst
                        .buf(TestModule.i32Const(0xAB))    // val
                        .buf(TestModule.i32Const(len))     // len
                        .raw(0xFC, 0x0B, 0x00))            // memory.fill
                .build();
    }

    @Test
    void bulkFillUnderfueledExhaustsWithoutMutatingThenResumeCompletes() {
        Instance inst = new Instance(Module.parse(fillModule(1000)), Map.of());
        ExecutionContext ctx = inst.instantiate();

        // Enough fuel to reach the fill (3 consts) but not to afford it (cost 1 + 1000/16).
        ExecResult first = inst.invoke(ctx, "main", new long[0], 10);
        assertInstanceOf(ExecResult.FuelExhausted.class, first);
        assertEquals(0, ctx.loadByte(0));   // nothing written
        assertEquals(0, ctx.loadByte(999));

        ExecResult second = inst.resume(ctx, 1_000_000);
        assertInstanceOf(ExecResult.Completed.class, second);
        assertEquals((byte) 0xAB, ctx.loadByte(0));   // completed on resume
        assertEquals((byte) 0xAB, ctx.loadByte(999));
    }

    @Test
    void bulkFillIsChargedProportionally() {
        long big = fuelConsumedByFill(1000);
        long small = fuelConsumedByFill(0);
        // The only difference is the fill's length-proportional cost: 1000 >>> 4 = 62.
        assertEquals(1000 >>> 4, big - small);
        assertTrue(big > small);
    }

    private static long fuelConsumedByFill(int len) {
        Instance inst = new Instance(Module.parse(fillModule(len)), Map.of());
        ExecutionContext ctx = inst.instantiate();
        ExecResult r = inst.invoke(ctx, "main", new long[0], 1_000_000);
        assertInstanceOf(ExecResult.Completed.class, r);
        return ctx.fuelConsumed();
    }
}
