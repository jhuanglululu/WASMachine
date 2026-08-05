package com.jhuanglululu.wasm;

import java.util.Arrays;

/**
 * The instance-wide shared static region: one bump-allocated buffer that every task's
 * {@link ExecutionContext} holds by <em>reference</em> rather than copying, so a fork never
 * duplicates it. It is the home for data that is written once and read by every task —
 * environ, and later any large read-only blob that would otherwise be cloned per task.
 *
 * <p><b>A second address window, not a second memory.</b> Guest pointers stay plain {@code i32}s.
 * An address at {@link #SHARED_BASE} or above routes here (at {@code addr - SHARED_BASE});
 * everything below routes to the task's private linear memory. Every reachable private address
 * is under 2<sup>31</sup>, so a shared address read as a signed {@code i32} is simply negative —
 * that sign bit is the whole routing test the interpreter runs on every load and store. The
 * compiler never produces such an address on its own; {@code engine.shared_alloc} is the only
 * way to obtain one, after which ordinary loads and stores reach it.
 *
 * <p><b>Nothing is ever freed.</b> {@link #allocate} only bumps. That is what lets the guest
 * hand the region out as {@code &'static} data, and it is why the SDK convention is to write it
 * during task 0's prologue — before any fork exists — and treat it as read-only afterwards.
 * Tasks run cooperatively, one at a time, so allocation needs no locking.
 */
public final class SharedRegion {

    /**
     * The fixed base of the shared window. As a guest {@code i32} this is {@code i32::MIN}, so
     * "the address is negative" and "the address is shared" are the same test.
     */
    public static final long SHARED_BASE = 0x8000_0000L;

    /**
     * Notified <em>before</em> the buffer grows, so an embedder can charge the bytes against its
     * own accounting. Throwing refuses the growth and leaves the region untouched.
     */
    @FunctionalInterface
    public interface Charger {
        void charge(long extraBytes);
    }

    /** What a context has until an embedder attaches a real region: no capacity at all. */
    static final SharedRegion NONE = new SharedRegion(0, bytes -> { });

    private final long capBytes;
    private final Charger charger;
    private byte[] bytes = new byte[0];

    /**
     * @param capBytes how far the region may ever grow, in bytes
     * @param charger  called with the byte delta each time the buffer grows — once per instance,
     *                 never per task, because the region has exactly one buffer
     */
    public SharedRegion(long capBytes, Charger charger) {
        if (capBytes < 0 || capBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("a shared region cap must be 0.."
                    + Integer.MAX_VALUE + " bytes, got " + capBytes);
        }
        this.capBytes = capBytes;
        this.charger = charger;
    }

    /** The configured ceiling. */
    public long capBytes() {
        return capBytes;
    }

    /**
     * Bytes handed out so far — and, exactly because growth is sized to the request, also the
     * region's addressable extent: reading past it traps like reading past linear memory.
     */
    public int size() {
        return bytes.length;
    }

    byte[] bytes() {
        return bytes;
    }

    /**
     * Bumps {@code size} bytes off the region, aligned up to {@code align}, and returns their
     * guest address ({@code >= SHARED_BASE}, hence negative as an {@code i32}).
     *
     * <p>Returns {@code 0} — never a valid shared address — when the request does not fit under
     * the cap, leaving the region untouched; the embedder decides how to fail. The growth is
     * exact rather than chunked: allocations happen a handful of times per instance, and paying
     * one array copy each keeps the charged bytes equal to the bytes the guest asked for.
     */
    public int allocate(int size, int align) {
        if (size < 0) {
            return 0;
        }
        long start = alignUp(bytes.length, Math.max(align, 1));
        long end = start + size;
        if (end > capBytes) {
            return 0;
        }
        charger.charge(end - bytes.length);
        bytes = Arrays.copyOf(bytes, (int) end);
        return (int) (SHARED_BASE + start);
    }

    private static long alignUp(long value, int align) {
        // align is a power of two in practice, but this form is correct for any align >= 1.
        return (value + align - 1) / align * align;
    }
}
