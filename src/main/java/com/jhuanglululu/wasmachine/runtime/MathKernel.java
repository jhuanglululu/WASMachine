package com.jhuanglululu.wasmachine.runtime;

import com.jhuanglululu.wasm.HostFunction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

/**
 * The engine math kernel: transcendentals and {@code f64} formatting as host calls.
 *
 * <p><b>Why these exist.</b> A transcendental compiles to a software routine costing ~500–1000
 * interpreted instructions; a host call costs tens. Plain arithmetic and
 * {@code sqrt/abs/floor/ceil/trunc/nearest} are native wasm opcodes and deliberately get no
 * kernel entry. Everything here is {@link StrictMath}-backed, so results are bit-identical
 * across machines — which is what makes a guest's whole trace reproducible.
 *
 * <p><b>What it is not.</b> Feature semantics never live here: the kernel supplies primitive
 * speed, never meaning. Domain errors follow {@code StrictMath} (NaN propagation, ±inf) and
 * <em>never</em> kill the instance — a caller that requires finiteness asserts it guest-side,
 * where the meaning lives. API misuse (a precision or capacity that cannot mean anything) is a
 * different thing entirely and does kill, like every other argument-range violation.
 *
 * <p>Stateless: every entry point is a pure function of its arguments and the calling task's
 * memory, so the kernel holds no instance state at all.
 */
public final class MathKernel {

    /** The most fractional digits {@link #formatF64} will produce (an {@code f64} carries ~17). */
    public static final int MAX_PRECISION = 17;

    /** The {@code precision} value asking for the shortest round-trip form instead of fixed digits. */
    public static final int SHORTEST = -1;

    private MathKernel() {}

    /** Registers the kernel under {@code module} (the engine's own import namespace). */
    static void addImports(Map<String, HostFunction> m, String module) {
        unary(m, module, "cbrt", StrictMath::cbrt);
        unary(m, module, "exp", StrictMath::exp);
        unary(m, module, "ln", StrictMath::log); // natural log; log10 is separate and sharper
        unary(m, module, "log10", StrictMath::log10);
        unary(m, module, "sin", StrictMath::sin);
        unary(m, module, "cos", StrictMath::cos);
        unary(m, module, "tan", StrictMath::tan);
        unary(m, module, "asin", StrictMath::asin);
        unary(m, module, "acos", StrictMath::acos);
        binary(m, module, "pow", StrictMath::pow);
        binary(m, module, "atan2", StrictMath::atan2); // (y, x), like the C and Rust signature
        m.put(module + ".format_f64", (ctx, a) -> {
            byte[] text = formatF64(Marshal.f64(a[0]), (int) a[1]).getBytes(StandardCharsets.UTF_8);
            int cap = (int) a[3];
            if (cap < 0) {
                throw new GuestAbort("format_f64: negative buffer capacity " + cap);
            }
            // Write what fits and report what was needed: the guest retries with a bigger
            // buffer. There is no blocking point between the two calls, so nothing can race.
            int write = Math.min(text.length, cap);
            if (write > 0) {
                ctx.writeBytes((int) a[2], Arrays.copyOf(text, write));
            }
            return text.length;
        });
    }

    /** A pure {@code (f64) -> f64} entry. */
    private static void unary(Map<String, HostFunction> m, String module, String name,
            java.util.function.DoubleUnaryOperator fn) {
        m.put(module + "." + name,
                (ctx, a) -> Marshal.f64Bits(fn.applyAsDouble(Marshal.f64(a[0]))));
    }

    /** A pure {@code (f64, f64) -> f64} entry. */
    private static void binary(Map<String, HostFunction> m, String module, String name,
            java.util.function.DoubleBinaryOperator fn) {
        m.put(module + "." + name,
                (ctx, a) -> Marshal.f64Bits(fn.applyAsDouble(Marshal.f64(a[0]), Marshal.f64(a[1]))));
    }

    /**
     * The canonical decimal text for {@code x} — the one function in the kernel whose output is
     * a <em>string</em>, and therefore the one that must be pinned across languages: the guest
     * crate's host-target stubs format with Rust's own {@code format!}, so Java has to agree
     * digit for digit. Cross-language test vectors exist on both sides for exactly this.
     *
     * <p>The rules, which are Rust's:
     * <ul>
     *   <li>Non-finite values are {@code NaN}, {@code inf}, {@code -inf} at any precision.</li>
     *   <li>{@link #SHORTEST} gives the shortest round-trip decimal in <em>plain</em> notation —
     *       never scientific, however large or small (Rust's {@code Display} never uses an
     *       exponent). {@code Double.toString} supplies the shortest digits; {@link BigDecimal}
     *       expands them.</li>
     *   <li>{@code 0..=}{@value #MAX_PRECISION} gives exactly that many fractional digits,
     *       rounded HALF_EVEN over the <em>exact</em> binary value — hence
     *       {@code new BigDecimal(double)} and not {@code BigDecimal.valueOf}, and not
     *       {@code String.format}, which rounds HALF_UP. This is what makes {@code 2.5 -> "2"}
     *       and {@code 1.005 -> "1.00"} come out the same in both languages.</li>
     * </ul>
     *
     * <p>Negative zero keeps its sign ({@code -0}, {@code -0.00}): {@code BigDecimal} has no
     * signed zero, so the sign is restored from the raw bits afterwards.
     *
     * @param precision {@link #SHORTEST}, or {@code 0..=}{@value #MAX_PRECISION}
     * @throws GuestAbort if {@code precision} is outside that range — API misuse, not a domain
     *                    error, so it kills like any other bad argument
     */
    public static String formatF64(double x, int precision) {
        if (precision < SHORTEST || precision > MAX_PRECISION) {
            throw new GuestAbort("format_f64: precision " + precision + " out of range "
                    + SHORTEST + ".." + MAX_PRECISION);
        }
        if (Double.isNaN(x)) {
            return "NaN";
        }
        if (Double.isInfinite(x)) {
            return x > 0 ? "inf" : "-inf";
        }
        String text = precision == SHORTEST
                ? new BigDecimal(Double.toString(x)).stripTrailingZeros().toPlainString()
                : new BigDecimal(x).setScale(precision, RoundingMode.HALF_EVEN).toPlainString();
        return signOf(x) < 0 && !text.startsWith("-") ? "-" + text : text;
    }

    /** {@code -1} for a negative value <em>including</em> negative zero, else {@code 1}. */
    private static int signOf(double x) {
        return Double.doubleToRawLongBits(x) < 0 ? -1 : 1;
    }
}
