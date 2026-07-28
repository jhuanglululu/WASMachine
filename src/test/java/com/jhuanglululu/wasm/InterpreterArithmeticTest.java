package com.jhuanglululu.wasm;

import static com.jhuanglululu.wasm.TestModule.f32;
import static com.jhuanglululu.wasm.TestModule.f32Const;
import static com.jhuanglululu.wasm.TestModule.f64;
import static com.jhuanglululu.wasm.TestModule.f64Const;
import static com.jhuanglululu.wasm.TestModule.i32;
import static com.jhuanglululu.wasm.TestModule.i32Const;
import static com.jhuanglululu.wasm.TestModule.i64;
import static com.jhuanglululu.wasm.TestModule.i64Const;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jhuanglululu.wasm.WasmBuilder.Buf;
import org.junit.jupiter.api.Test;

/** Known-answer tests for numeric instructions, focused on the trap-edge semantics. */
class InterpreterArithmeticTest {

    private static TrapReason trapReason(int resultType, Buf instrs) {
        ExecResult r = TestModule.exec(resultType, instrs);
        assertInstanceOf(ExecResult.Trapped.class, r, () -> "expected trap but was " + r);
        return ((ExecResult.Trapped) r).reason();
    }

    private static Buf op(Buf a, Buf b, int opcode) {
        return new Buf().buf(a).buf(b).raw(opcode);
    }

    @Test
    void basicI32Arithmetic() {
        assertEquals(7, i32(op(i32Const(3), i32Const(4), 0x6A)));   // add
        assertEquals(-1, i32(op(i32Const(3), i32Const(4), 0x6B)));  // sub
        assertEquals(12, i32(op(i32Const(3), i32Const(4), 0x6C)));  // mul
        assertEquals(2, i32(op(i32Const(7), i32Const(3), 0x6D)));   // div_s
    }

    @Test
    void i32DivRemTraps() {
        assertEquals(TrapReason.INTEGER_DIVIDE_BY_ZERO, trapReason(TestModule.I32,
                op(i32Const(1), i32Const(0), 0x6D)));
        assertEquals(TrapReason.INTEGER_OVERFLOW, trapReason(TestModule.I32,
                op(i32Const(Integer.MIN_VALUE), i32Const(-1), 0x6D)));
        assertEquals(TrapReason.INTEGER_DIVIDE_BY_ZERO, trapReason(TestModule.I32,
                op(i32Const(1), i32Const(0), 0x6F))); // rem_s by zero
    }

    @Test
    void i32RemSignedMinByMinusOneIsZeroNoTrap() {
        // MIN % -1 == 0 (must not trap, unlike div).
        assertEquals(0, i32(op(i32Const(Integer.MIN_VALUE), i32Const(-1), 0x6F)));
    }

    @Test
    void i64DivOverflowTrapsAndRemDoesNot() {
        assertEquals(TrapReason.INTEGER_OVERFLOW, trapReason(TestModule.I64,
                op(i64Const(Long.MIN_VALUE), i64Const(-1), 0x7F)));
        assertEquals(0L, i64(op(i64Const(Long.MIN_VALUE), i64Const(-1), 0x81))); // rem_s
    }

    @Test
    void unsignedDivRem() {
        // div_u: 0xFFFFFFFF / 2 = 0x7FFFFFFF (2147483647), signed would be -1/2.
        assertEquals(0x7FFFFFFF, i32(op(i32Const(-1), i32Const(2), 0x6E)));
        // rem_u: 0xFFFFFFFF % 10 = 5 (4294967295 % 10).
        assertEquals(5, i32(op(i32Const(-1), i32Const(10), 0x70)));
        assertEquals(TrapReason.INTEGER_DIVIDE_BY_ZERO, trapReason(TestModule.I32,
                op(i32Const(1), i32Const(0), 0x6E)));
    }

    @Test
    void unsignedComparisons() {
        // lt_u: 1 < 0xFFFFFFFF unsigned -> true; lt_s would be false.
        assertEquals(1, i32(op(i32Const(1), i32Const(-1), 0x49)));
        assertEquals(0, i32(op(i32Const(1), i32Const(-1), 0x48))); // lt_s
    }

    @Test
    void shiftCountsAreMasked() {
        // i32.shl by 33 == shl by 1.
        assertEquals(2, i32(op(i32Const(1), i32Const(33), 0x74)));
        // i64.shl by 65 == shl by 1.
        assertEquals(2L, i64(op(i64Const(1), i64Const(65), 0x86)));
        // shr_u by 33 == shr_u by 1: 0xFFFFFFFF >>> 1 == 0x7FFFFFFF.
        assertEquals(0x7FFFFFFF, i32(op(i32Const(-1), i32Const(33), 0x76)));
    }

    @Test
    void rotate() {
        // rotl 0x12345678 by 8 -> 0x34567812.
        assertEquals(0x34567812, i32(op(i32Const(0x12345678), i32Const(8), 0x77)));
        // rotr 0x12345678 by 8 -> 0x78123456.
        assertEquals(0x78123456, i32(op(i32Const(0x12345678), i32Const(8), 0x78)));
    }

    @Test
    void clzCtzPopcnt() {
        assertEquals(28, i32(new Buf().buf(i32Const(8)).raw(0x67)));  // clz(8)
        assertEquals(3, i32(new Buf().buf(i32Const(8)).raw(0x68)));   // ctz(8)
        assertEquals(4, i32(new Buf().buf(i32Const(0xF0)).raw(0x69))); // popcnt
    }

    @Test
    void floatMinMaxNanAndSignedZero() {
        // min(-0.0, 0.0) == -0.0 (check the sign bit).
        float minZero = f32(op(f32Const(-0.0f), f32Const(0.0f), 0x96));
        assertEquals(Integer.MIN_VALUE, Float.floatToRawIntBits(minZero)); // -0.0f bits
        // max(-0.0, 0.0) == +0.0.
        float maxZero = f32(op(f32Const(-0.0f), f32Const(0.0f), 0x97));
        assertEquals(0, Float.floatToRawIntBits(maxZero));
        // min with NaN -> NaN.
        assertTrue(Float.isNaN(f32(op(f32Const(Float.NaN), f32Const(1.0f), 0x96))));
        assertTrue(Double.isNaN(f64(op(f64Const(1.0), f64Const(Double.NaN), 0xA4))));
    }

    @Test
    void nearestRoundsHalfToEven() {
        assertEquals(2.0f, f32(new Buf().buf(f32Const(2.5f)).raw(0x90)));
        assertEquals(4.0f, f32(new Buf().buf(f32Const(3.5f)).raw(0x90)));
        assertEquals(-2.0, f64(new Buf().buf(f64Const(-2.5)).raw(0x9E)));
        assertEquals(2.0, f64(new Buf().buf(f64Const(2.4)).raw(0x9E)));
    }

    @Test
    void copysignAndTrunc() {
        assertEquals(-3.0f, f32(op(f32Const(3.0f), f32Const(-1.0f), 0x98)));
        assertEquals(2.0f, f32(new Buf().buf(f32Const(2.9f)).raw(0x8F)));   // trunc
        assertEquals(-2.0, f64(new Buf().buf(f64Const(-2.9)).raw(0x9D)));   // trunc toward zero
    }

    @Test
    void nonSaturatingTruncTraps() {
        // NaN -> invalid conversion.
        assertEquals(TrapReason.INVALID_CONVERSION_TO_INTEGER, trapReason(TestModule.I32,
                new Buf().buf(f32Const(Float.NaN)).raw(0xA8)));
        // 2^31 is out of i32 signed range -> overflow.
        assertEquals(TrapReason.INTEGER_OVERFLOW, trapReason(TestModule.I32,
                new Buf().buf(f32Const(0x1p31f)).raw(0xA8)));
        // -1.0 out of unsigned range -> overflow.
        assertEquals(TrapReason.INTEGER_OVERFLOW, trapReason(TestModule.I32,
                new Buf().buf(f32Const(-1.0f)).raw(0xA9)));
    }

    @Test
    void truncInRange() {
        assertEquals(-2147483648, i32(new Buf().buf(f64Const(-2147483648.0)).raw(0xAA))); // i32.trunc_f64_s
        assertEquals(42, i32(new Buf().buf(f64Const(42.9)).raw(0xAA)));
        assertEquals(-1, i32(new Buf().buf(f64Const(4294967295.0)).raw(0xAB))); // i32.trunc_f64_u -> 0xFFFFFFFF
    }

    @Test
    void saturatingTrunc() {
        // NaN -> 0.
        assertEquals(0, i32(new Buf().buf(f32Const(Float.NaN)).raw(0xFC, 0x00)));
        // +inf saturates to MAX.
        assertEquals(Integer.MAX_VALUE, i32(new Buf().buf(f32Const(Float.POSITIVE_INFINITY)).raw(0xFC, 0x00)));
        // -inf saturates to MIN (signed).
        assertEquals(Integer.MIN_VALUE, i32(new Buf().buf(f32Const(Float.NEGATIVE_INFINITY)).raw(0xFC, 0x00)));
        // unsigned: -5.0 saturates to 0.
        assertEquals(0, i32(new Buf().buf(f32Const(-5.0f)).raw(0xFC, 0x01)));
        // unsigned huge saturates to 0xFFFFFFFF.
        assertEquals(-1, i32(new Buf().buf(f64Const(1e19)).raw(0xFC, 0x03)));
        // i64 saturating.
        assertEquals(Long.MAX_VALUE, i64(new Buf().buf(f64Const(1e300)).raw(0xFC, 0x06)));
    }

    @Test
    void conversionsAndReinterpret() {
        // Opcode numbers here are the SPEC values (verified against a real artifact),
        // not derived from the interpreter.
        // i64.extend_i32_u (0xAD): 0xFFFFFFFF -> 0x00000000FFFFFFFF.
        assertEquals(0xFFFFFFFFL, i64(new Buf().buf(i32Const(-1)).raw(0xAD)));
        // i64.extend_i32_s (0xAC): 0xFFFFFFFF -> -1.
        assertEquals(-1L, i64(new Buf().buf(i32Const(-1)).raw(0xAC)));
        // f32.convert_i32_u (0xB3) of -1 -> 2^32.
        assertEquals(0x1p32f, f32(new Buf().buf(i32Const(-1)).raw(0xB3)));
        // f32.convert_i64_u (0xB5) of -1 -> 2^64.
        assertEquals(0x1p64f, f32(new Buf().buf(i64Const(-1)).raw(0xB5)));
        // f32.demote_f64 (0xB6) of 1.5 -> 1.5f.
        assertEquals(1.5f, f32(new Buf().buf(f64Const(1.5)).raw(0xB6)));
        // f64.convert_i32_s (0xB7) of -1 -> -1.0.
        assertEquals(-1.0, f64(new Buf().buf(i32Const(-1)).raw(0xB7)));
        // f64.convert_i32_u (0xB8) of -1 -> 4294967295.0 (0xFFFFFFFF unsigned).
        assertEquals(4294967295.0, f64(new Buf().buf(i32Const(-1)).raw(0xB8)));
        // f64.convert_i64_s (0xB9) of -2 -> -2.0 (this is the demo's panel-x op).
        assertEquals(-2.0, f64(new Buf().buf(i64Const(-2)).raw(0xB9)));
        // f64.convert_i64_u (0xBA) of -1 -> 1.8446744073709552E19.
        assertEquals(1.8446744073709552E19, f64(new Buf().buf(i64Const(-1)).raw(0xBA)));
        // f64.promote_f32 (0xBB) of 1.5f -> 1.5.
        assertEquals(1.5, f64(new Buf().buf(f32Const(1.5f)).raw(0xBB)));
        // i32.reinterpret_f32 (0xBC) of 1.0f -> 0x3F800000.
        assertEquals(0x3F800000, i32(new Buf().buf(f32Const(1.0f)).raw(0xBC)));
        // f64.reinterpret_i64 (0xBF) of i64 bits of 2.5 round-trips.
        assertEquals(2.5, f64(new Buf().buf(i64Const(Double.doubleToRawLongBits(2.5))).raw(0xBF)));
    }

    @Test
    void convertI64SignedFromLocalAddNegative() {
        // Mirrors the demo's panel-x sequence exactly: (local i64 = 0) + (-2), then
        // f64.convert_i64_s (0xB9). Expected -2.0; the earlier opcode-table bug showed 2^64.
        byte[] bytes = new TestModule()
                .types(new Buf().vec(1).raw(0x60, 0x00, 0x01, 0x7C)) // () -> f64
                .locals(new Buf().vec(1).uleb(1).raw(0x7E))          // 1 i64 local (= 0)
                .body(new Buf().raw(0x20, 0x00, 0x42, 0x7E, 0x7C, 0xB9)) // local.get0, i64.const -2, i64.add, convert_i64_s
                .build();
        Instance inst = new Instance(Module.parse(bytes), java.util.Map.of());
        ExecResult r = inst.invoke(inst.instantiate(), "main", new long[0], 100_000);
        double got = Double.longBitsToDouble(((ExecResult.Completed) r).values()[0]);
        assertEquals(-2.0, got);
    }

    @Test
    void signExtensionOps() {
        assertEquals(-1, i32(new Buf().buf(i32Const(0xFF)).raw(0xC0)));       // extend8_s
        assertEquals(-1, i32(new Buf().buf(i32Const(0xFFFF)).raw(0xC1)));     // extend16_s
        assertEquals(-1L, i64(new Buf().buf(i64Const(0xFF)).raw(0xC2)));      // i64.extend8_s
        assertEquals(0x7F, i32(new Buf().buf(i32Const(0x7F)).raw(0xC0)));     // positive unchanged
    }

    @Test
    void unreachableTraps() {
        assertEquals(TrapReason.UNREACHABLE, trapReason(TestModule.I32,
                new Buf().raw(0x00).buf(i32Const(0)))); // unreachable then (dead) const
    }
}
