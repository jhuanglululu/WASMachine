package com.jhuanglululu.wasmachine.runtime;

/**
 * Thrown by {@link HostAllocator} when an allocation would push the animation's heap
 * past its configured per-instance byte cap (or beyond what linear memory can grow to).
 * A subtype of {@link GuestAbort}, so it surfaces as a {@link TickResult.Errored}.
 */
public final class MemoryCapExceededException extends GuestAbort {

    public MemoryCapExceededException(String message) {
        super(message);
    }
}
