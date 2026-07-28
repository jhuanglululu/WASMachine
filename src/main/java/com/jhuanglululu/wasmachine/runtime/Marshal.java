package com.jhuanglululu.wasmachine.runtime;

import com.jhuanglululu.wasm.ExecutionContext;
import java.nio.charset.StandardCharsets;

/**
 * The handful of marshalling moves every host import makes across the ABI: guest strings are
 * {@code (ptr, len)} pairs of UTF-8 bytes, {@code f64} arguments arrive as raw bits in the
 * {@code long} argument slots, and multi-value results are written back into guest memory as
 * little-endian doubles.
 *
 * <p>It lives in the engine because both sides need it: the engine's own imports
 * ({@code log}, {@code fail}, {@code channel_*}) and every plugin import a
 * {@link MachineInstance} embedder registers.
 */
public final class Marshal {

    private Marshal() {}

    /** Reads {@code len} bytes of guest memory at {@code ptr} as a UTF-8 string. */
    public static String readString(ExecutionContext ctx, int ptr, int len) {
        return new String(ctx.readBytes(ptr, len), StandardCharsets.UTF_8);
    }

    /** Writes {@code values} as consecutive little-endian {@code f64}s starting at {@code addr}. */
    public static void writeDoubles(ExecutionContext ctx, int addr, double[] values) {
        for (int i = 0; i < values.length; i++) {
            long b = Double.doubleToRawLongBits(values[i]);
            ctx.storeI32(addr + i * 8, (int) b);
            ctx.storeI32(addr + i * 8 + 4, (int) (b >>> 32));
        }
    }

    /** The {@code f64} an argument slot carries (arguments cross the ABI as raw bits). */
    public static double f64(long raw) {
        return Double.longBitsToDouble(raw);
    }

    /** The raw bits to return an {@code f64} result through a host function. */
    public static long f64Bits(double value) {
        return Double.doubleToRawLongBits(value);
    }
}
