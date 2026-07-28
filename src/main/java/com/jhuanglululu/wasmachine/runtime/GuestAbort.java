package com.jhuanglululu.wasmachine.runtime;

/**
 * Thrown by a host import to abort the whole instance with a message: {@code fail},
 * a plugin validator rejection, use of a dead/unknown resource, or an allocator
 * failure. The instance core catches it and turns it into a {@link TickResult.Errored}.
 * It is a plain error signal, not caught by the interpreter's suspend machinery.
 */
public class GuestAbort extends RuntimeException {

    public GuestAbort(String message) {
        super(message);
    }
}
