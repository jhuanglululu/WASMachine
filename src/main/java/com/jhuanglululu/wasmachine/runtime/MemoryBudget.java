package com.jhuanglululu.wasmachine.runtime;

/**
 * The animation's single memory allowance. Both the guest heaps ({@link HostAllocator}, one per
 * task) and the host-side channel buffers ({@link SyncTable}) charge the same counter, because the
 * configured cap is documented as a <em>per-instance</em> cap: an animation must not be able to
 * hold a full cap of heap and then a second full cap of queued messages.
 *
 * <p>Charging every task's heap rather than capping each separately is the same rule read
 * honestly — a forked task's memory is a real second copy, so an instance's footprint is the sum
 * over its tasks.
 *
 * <p>Reservations are all-or-nothing: a refused one throws {@link MemoryCapExceededException} and
 * consumes nothing, so the caller's own bookkeeping stays consistent.
 *
 * <p>Thread-safe by synchronization. One instance runs on one worker at a time, but its cleanup
 * paths run on the main thread, so the counter crosses that boundary.
 */
public final class MemoryBudget {

    private final long capBytes;
    private long used;

    public MemoryBudget(long capBytes) {
        this.capBytes = capBytes;
    }

    /** The configured per-instance cap. */
    public long capBytes() {
        return capBytes;
    }

    /** Bytes currently charged (guest heaps plus channel buffers). */
    public synchronized long used() {
        return used;
    }

    /**
     * Charges {@code bytes} against the cap.
     *
     * @param what what the bytes are for, named in the failure message
     * @throws MemoryCapExceededException if the total would exceed the cap; nothing is charged
     */
    public synchronized void reserve(long bytes, String what) {
        long total = used + bytes;
        if (total > capBytes) {
            throw new MemoryCapExceededException("animation memory cap of " + capBytes
                    + " bytes exceeded: " + what + " needs " + bytes + " more byte(s) on top of "
                    + used + " already held");
        }
        used = total;
    }

    /** Gives {@code bytes} back (a freed heap block, a dequeued message, a finished task). */
    public synchronized void release(long bytes) {
        used -= bytes;
    }
}
