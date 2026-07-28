package com.jhuanglululu.wasmachine.runtime;

import static com.jhuanglululu.wasmachine.runtime.SyncWasm.CHANNEL_NEW;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.REALLOC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jhuanglululu.wasmachine.runtime.SyncWasm.P;
import org.junit.jupiter.api.Test;

/**
 * The per-instance memory cap is <em>one</em> budget. Both docs say channel payload bytes count
 * toward the instance memory cap, so a guest must not be able to hold a full cap of heap and a
 * second full cap of channel buffers.
 */
class MemoryBudgetTest {

    private static final long CAP = 4096;

    /** Allocates {@code size} bytes on the guest heap and drops the pointer. */
    private static P allocate(int size) {
        return new P().i32(0).i32(0).i32(8).i32(size).call(REALLOC).drop();
    }

    /** Sends {@code len} bytes from the letter table into a fresh channel (local 0 = channel id). */
    private static P sendBytes(int len) {
        return new P().i32(1000).call(CHANNEL_NEW).set(0).send(0, 0, len);
    }

    @Test
    void heapAloneCanFillTheCap() {
        SyncRun.run(allocate(3000), CAP, 0L, 3, 10_000_000L).assertFinished();
    }

    @Test
    void channelBytesAloneCanFillTheCap() {
        SyncRun.run(sendBytes(3000), CAP, 0L, 3, 10_000_000L).assertFinished();
    }

    @Test
    void heapPlusChannelBytesShareOneCap() {
        // 3000 bytes of heap and 1200 bytes of channel payload each fit under a 4096-byte cap, but
        // together they are 4200 — over the one budget the animation is allowed.
        P main = new P().append(allocate(3000)).append(sendBytes(1200));
        SyncRun.run(main, CAP, 0L, 3, 10_000_000L).assertKilled("memory cap");
    }

    @Test
    void channelBytesThenHeapAlsoShareOneCap() {
        // The same overflow reached from the other side, so neither charger is special.
        P main = new P().append(sendBytes(1200)).append(allocate(3000));
        SyncRun.run(main, CAP, 0L, 3, 10_000_000L).assertKilled("memory cap");
    }

    // --- the budget object itself ---

    @Test
    void reserveAndReleaseTrackTheTotal() {
        MemoryBudget budget = new MemoryBudget(100);
        budget.reserve(60, "heap");
        assertEquals(60, budget.used());
        budget.reserve(40, "channel");
        assertEquals(100, budget.used(), "exactly the cap is allowed");
        budget.release(40);
        assertEquals(60, budget.used());
        budget.reserve(40, "channel");
        assertEquals(100, budget.used(), "released bytes are reusable");
    }

    @Test
    void reserveBeyondTheCapThrowsAndChangesNothing() {
        MemoryBudget budget = new MemoryBudget(100);
        budget.reserve(90, "heap");
        MemoryCapExceededException e = assertThrows(MemoryCapExceededException.class,
                () -> budget.reserve(20, "channel buffers"));
        assertEquals(90, budget.used(), "a refused reservation must not consume budget");
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("channel buffers"),
                "the message must name what was being reserved: " + e.getMessage());
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("100"),
                "and the cap: " + e.getMessage());
    }
}
