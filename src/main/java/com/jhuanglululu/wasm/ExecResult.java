package com.jhuanglululu.wasm;

/**
 * The outcome of a {@link Instance#resume} / {@link Instance#invoke} step. Exactly
 * one variant is returned; {@link Completed} and {@link Trapped} are terminal, while
 * {@link Suspended} and {@link FuelExhausted} leave the {@link ExecutionContext}
 * resumable in place.
 */
public sealed interface ExecResult
        permits ExecResult.Completed, ExecResult.Suspended, ExecResult.Trapped, ExecResult.FuelExhausted {

    /** The invoked function returned; {@code values} are its results (raw 64-bit slots). */
    @SuppressWarnings("ArrayRecordComponent") // identity equality is fine; result values are read once
    record Completed(long[] values) implements ExecResult {}

    /**
     * A host function suspended the context. {@code request} is the opaque payload the
     * host passed to {@link ExecutionContext#suspend}. Resume with
     * {@link Instance#resume(ExecutionContext, long, long)} supplying the value the
     * host call should return.
     */
    record Suspended(Object request) implements ExecResult {}

    /** Execution trapped; {@code reason} classifies it and {@code message} details it. */
    record Trapped(TrapReason reason, String message) implements ExecResult {}

    /** The instruction budget ran out. The context is intact; resume with more fuel. */
    record FuelExhausted() implements ExecResult {}
}
