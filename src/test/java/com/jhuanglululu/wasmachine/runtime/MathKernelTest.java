package com.jhuanglululu.wasmachine.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jhuanglululu.wasm.ExecutionContext;
import com.jhuanglululu.wasm.HostFunction;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The math kernel as registered import functions, and the {@code format_f64} text contract.
 *
 * <p>The vector table below is <b>pinned across languages</b>: the {@code wasmachine} Rust crate
 * embeds the identical table against its native {@code format!} stubs, the same way SplitMix64
 * is mirrored. Every entry is a case where a naive implementation diverges — half-even ties on
 * the exact binary value ({@code 2.5 -> "2"}, {@code 1.005 -> "1.00"}), negative zero keeping
 * its sign, plain-notation extremes, and the non-finite spellings Rust uses.
 */
class MathKernelTest {

    private static final String ENGINE = "engine";

    private static final Map<String, HostFunction> IMPORTS = registered();

    /** A real context: the kernel never touches memory except in {@code format_f64}. */
    private static final ExecutionContext CTX = RuntimeWasm.memoryContext(1);

    private static Map<String, HostFunction> registered() {
        Map<String, HostFunction> m = new HashMap<>();
        MathKernel.addImports(m, ENGINE);
        return m;
    }

    private static double call(String name, double... args) {
        long[] slots = new long[args.length];
        for (int i = 0; i < args.length; i++) {
            slots[i] = Marshal.f64Bits(args[i]);
        }
        HostFunction fn = IMPORTS.get(ENGINE + "." + name);
        assertTrue(fn != null, "the kernel registered no " + ENGINE + "." + name);
        return Marshal.f64(fn.invoke(CTX, slots));
    }

    // --- the cross-language format_f64 vectors ---

    static Stream<Arguments> vectors() {
        return Stream.of(
                Arguments.of(2.5, 0, "2"),
                Arguments.of(3.5, 0, "4"),
                Arguments.of(-2.5, 0, "-2"),
                Arguments.of(0.125, 2, "0.12"),
                Arguments.of(0.375, 2, "0.38"),
                Arguments.of(1.005, 2, "1.00"),
                Arguments.of(0.25, 1, "0.2"),
                Arguments.of(0.75, 1, "0.8"),
                Arguments.of(-0.0, 2, "-0.00"),
                Arguments.of(-0.0, -1, "-0"),
                Arguments.of(1.0, -1, "1"),
                Arguments.of(0.1, -1, "0.1"),
                Arguments.of(1.5, -1, "1.5"),
                Arguments.of(1e7, -1, "10000000"),
                Arguments.of(1.5e-7, -1, "0.00000015"),
                Arguments.of(1e300, -1, "1" + "0".repeat(300)),
                Arguments.of(Double.NaN, 3, "NaN"),
                Arguments.of(Double.POSITIVE_INFINITY, -1, "inf"),
                Arguments.of(Double.NEGATIVE_INFINITY, 0, "-inf"),
                Arguments.of(0.3, 17, "0.29999999999999999"));
    }

    @ParameterizedTest(name = "format_f64({0}, {1})")
    @MethodSource("vectors")
    void formatMatchesThePinnedCrossLanguageVectors(double x, int precision, String expected) {
        assertEquals(expected, MathKernel.formatF64(x, precision));
    }

    @Test
    void shortestFormIsPlainNotationHoweverExtremeTheValue() {
        // Rust's Display never prints an exponent, so neither may this — the failure mode is
        // Double.toString's "1.0E300" leaking into a TextDisplay.
        assertTrue(MathKernel.formatF64(1e-300, MathKernel.SHORTEST).startsWith("0.000"));
        assertEquals(301, MathKernel.formatF64(1e300, MathKernel.SHORTEST).length());
        assertEquals("12345.6789", MathKernel.formatF64(12345.6789, MathKernel.SHORTEST));
    }

    @Test
    void fixedPrecisionRoundsOnTheExactBinaryValue() {
        // 2.675 is really 2.67499999…, so half-up (String.format) would say 2.68 and disagree
        // with Rust; the whole reason the implementation uses new BigDecimal(double).
        assertEquals("2.67", MathKernel.formatF64(2.675, 2));
        assertEquals("0.00", MathKernel.formatF64(0.0, 2));
        assertEquals("-1.00", MathKernel.formatF64(-1.0, 2));
    }

    @Test
    void precisionOutsideTheSupportedRangeKills() {
        // API misuse, unlike a math domain error: it kills, and the message names the range.
        GuestAbort tooMany = assertThrows(GuestAbort.class, () -> MathKernel.formatF64(1.0, 18));
        assertTrue(tooMany.getMessage().contains("18") && tooMany.getMessage().contains("-1..17"),
                tooMany.getMessage());
        assertThrows(GuestAbort.class, () -> MathKernel.formatF64(1.0, -2));
        // The bounds themselves are fine.
        assertEquals("1", MathKernel.formatF64(1.0, 0));
        assertEquals("1.00000000000000000", MathKernel.formatF64(1.0, 17));
    }

    // --- the plain StrictMath delegations ---

    @Test
    void everyUnaryEntryDelegatesToStrictMath() {
        assertEquals(-2.0, call("cbrt", -8.0), "cbrt is separate from pow precisely for this");
        assertEquals(StrictMath.exp(1.0), call("exp", 1.0));
        assertEquals(1.0, call("ln", StrictMath.E));
        assertEquals(3.0, call("log10", 1000.0));
        assertEquals(StrictMath.sin(0.7), call("sin", 0.7));
        assertEquals(StrictMath.cos(0.7), call("cos", 0.7));
        assertEquals(StrictMath.tan(0.7), call("tan", 0.7));
        assertEquals(StrictMath.asin(0.5), call("asin", 0.5));
        assertEquals(StrictMath.acos(0.5), call("acos", 0.5));
        assertEquals(0.0, call("sin", 0.0));
        assertEquals(1.0, call("cos", 0.0));
    }

    @Test
    void binaryEntriesTakeTheirArgumentsInAbiOrder() {
        assertEquals(8.0, call("pow", 2.0, 3.0), "pow(x, y) = x^y");
        // atan2(y, x), like C and Rust: atan2(1, 0) is +pi/2, not 0.
        assertEquals(StrictMath.atan2(1.0, 0.0), call("atan2", 1.0, 0.0));
        assertEquals(StrictMath.PI / 2, call("atan2", 1.0, 0.0), 1e-15);
        assertEquals(StrictMath.atan2(1.0, 2.0), call("atan2", 1.0, 2.0));
    }

    @Test
    void domainErrorsPropagateAsNaNOrInfinityAndNeverKill() {
        // The kernel supplies primitive speed, not meaning: a caller that needs finiteness
        // asserts it guest-side, where the meaning lives.
        assertTrue(Double.isNaN(call("ln", -1.0)));
        assertTrue(Double.isNaN(call("asin", 2.0)));
        assertTrue(Double.isNaN(call("acos", -2.0)));
        assertTrue(Double.isNaN(call("pow", -8.0, 1.0 / 3.0)), "why cbrt is its own entry");
        assertEquals(Double.NEGATIVE_INFINITY, call("ln", 0.0));
        assertEquals(Double.POSITIVE_INFINITY, call("exp", 1e6));
        for (String name : new String[] {"cbrt", "exp", "ln", "log10", "sin", "cos", "tan",
                "asin", "acos"}) {
            assertTrue(Double.isNaN(call(name, Double.NaN)), name + " must propagate NaN");
        }
        assertTrue(Double.isNaN(call("pow", Double.NaN, 2.0)));
        assertTrue(Double.isNaN(call("atan2", Double.NaN, 1.0)));
    }
}
