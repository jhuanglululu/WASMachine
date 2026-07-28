package com.jhuanglululu.wasm;

/**
 * Thrown by a {@link HostFunction} (via {@link ExecutionContext#suspend}) to suspend
 * the execution context in the middle of a host call instead of returning a value.
 *
 * <p>The interpreter catches this exactly at the host-call boundary — no guest frame
 * is ever on the Java call stack (guest calls use explicit frames), so the throw only
 * unwinds the single Java call into the dispatch loop. The context is left resumable;
 * {@link Instance#resume(ExecutionContext, long, long)} supplies the value that the
 * suspended host call returns. Stack trace capture is disabled: this is control flow,
 * not an error.
 */
public final class SuspendSignal extends RuntimeException {

    private final transient Object request;

    public SuspendSignal(Object request) {
        super(null, null, false, false);
        this.request = request;
    }

    /** The opaque payload the host attached; surfaced in {@link ExecResult.Suspended}. */
    public Object request() {
        return request;
    }
}
