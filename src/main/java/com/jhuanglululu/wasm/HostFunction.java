package com.jhuanglululu.wasm;

/**
 * A host (imported) function implemented in Java. It receives the calling
 * {@link ExecutionContext} (for memory/global access) and the argument slots (one
 * {@code long} per WASM argument, in declaration order; floats are the raw bits).
 *
 * <p>Return a single {@code long} result (the raw bits; ignored if the import's type
 * has no result). To suspend the animation instead of returning — the mechanism the
 * scheduler uses for {@code sleep}/{@code join} — throw {@link ExecutionContext#suspend}:
 *
 * <pre>{@code
 * (ctx, args) -> { throw ctx.suspend(new SleepRequest(args[0])); }
 * }</pre>
 */
@FunctionalInterface
public interface HostFunction {

    /**
     * Handles one call from the guest to this imported function.
     *
     * @param ctx  the suspendable execution context making the call
     * @param args argument slots (raw 64-bit values), length = the import's parameter count
     * @return the result slot (raw 64-bit); ignored when the import has no result
     */
    long invoke(ExecutionContext ctx, long[] args);
}
