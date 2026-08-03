package com.jhuanglululu.wasmachine.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jhuanglululu.wasm.ExecutionContext;
import org.junit.jupiter.api.Test;

/** Unit tests for the host-side allocator: data preservation, reuse, coalescing, alignment, cap, grow. */
class HostAllocatorTest {

    private static byte[] pattern(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i * 7 + 1);
        }
        return b;
    }

    @Test
    void allocReturnsAlignedWritableMemory() {
        ExecutionContext ctx = RuntimeWasm.memoryContext(2);
        HostAllocator a = new HostAllocator(1024, 1 << 20);
        int p = a.realloc(ctx, 0, 0, 8, 100);
        assertTrue(p >= 1024);
        assertEquals(0, p % 8);
        byte[] data = pattern(100);
        ctx.writeBytes(p, data);
        assertArrayEquals(data, ctx.readBytes(p, 100));
    }

    @Test
    void freedBlockIsReused() {
        ExecutionContext ctx = RuntimeWasm.memoryContext(2);
        HostAllocator a = new HostAllocator(1024, 1 << 20);
        int p1 = a.realloc(ctx, 0, 0, 8, 64);
        a.realloc(ctx, p1, 64, 8, 0); // free
        int p2 = a.realloc(ctx, 0, 0, 8, 64);
        assertEquals(p1, p2);
    }

    @Test
    void adjacentFreesCoalesce() {
        ExecutionContext ctx = RuntimeWasm.memoryContext(2);
        HostAllocator a = new HostAllocator(1024, 1 << 20);
        int a1 = a.realloc(ctx, 0, 0, 1, 16);
        int b1 = a.realloc(ctx, 0, 0, 1, 16);
        int c1 = a.realloc(ctx, 0, 0, 1, 16);
        assertEquals(a1 + 16, b1);
        assertEquals(b1 + 16, c1);
        // Free a and b; the two 16-byte holes must coalesce into one 32-byte hole at a1.
        a.realloc(ctx, a1, 16, 1, 0);
        a.realloc(ctx, b1, 16, 1, 0);
        int big = a.realloc(ctx, 0, 0, 1, 32);
        assertEquals(a1, big);
    }

    @Test
    void alignmentPadsAndAlignsPointer() {
        ExecutionContext ctx = RuntimeWasm.memoryContext(2);
        HostAllocator a = new HostAllocator(1000, 1 << 20); // heapBase not 16-aligned
        int p = a.realloc(ctx, 0, 0, 16, 10);
        assertEquals(0, p % 16);
        assertTrue(p >= 1000);
    }

    @Test
    void capBreachThrows() {
        ExecutionContext ctx = RuntimeWasm.memoryContext(2);
        HostAllocator a = new HostAllocator(1024, 100); // 100-byte cap
        a.realloc(ctx, 0, 0, 1, 50); // ok
        assertThrows(MemoryCapExceededException.class, () -> a.realloc(ctx, 0, 0, 1, 60));
    }

    @Test
    void allocationGrowsLinearMemory() {
        ExecutionContext ctx = RuntimeWasm.memoryContext(1); // one 64 KiB page
        int before = ctx.memorySize();
        HostAllocator a = new HostAllocator(1024, 1 << 24);
        int p = a.realloc(ctx, 0, 0, 8, 200_000); // must grow past one page
        assertTrue(ctx.memorySize() > before);
        assertTrue(ctx.memorySize() >= 1024 + 200_000);
        ctx.writeBytes(p, pattern(1000)); // proves the region is backed by real memory
    }

    @Test
    void reallocPreservesData() {
        ExecutionContext ctx = RuntimeWasm.memoryContext(2);
        HostAllocator a = new HostAllocator(1024, 1 << 20);
        int p = a.realloc(ctx, 0, 0, 8, 16);
        byte[] data = pattern(16);
        ctx.writeBytes(p, data);
        int grown = a.realloc(ctx, p, 16, 8, 64);
        assertArrayEquals(data, ctx.readBytes(grown, 16));
    }

    @Test
    void freeReturnsAHostAllocatedBlockToTheFreeList() {
        // The host's own free path (used for a task's stack region) must behave exactly like
        // the guest's realloc-to-zero: the block goes back and the next request reuses it.
        ExecutionContext ctx = RuntimeWasm.memoryContext(2);
        MemoryBudget budget = new MemoryBudget(1 << 20);
        HostAllocator a = new HostAllocator(1024, budget);
        int keep = a.realloc(ctx, 0, 0, 8, 32);
        int block = a.realloc(ctx, 0, 0, 8, 64);
        assertEquals(keep + 32, block);
        long chargedWithBoth = budget.used();

        a.free(block, 64);
        assertEquals(chargedWithBoth - 64, budget.used(), "the freed block gave its bytes back");
        assertEquals(block, a.realloc(ctx, 0, 0, 8, 64), "the same address is handed out again");
    }

    @Test
    void freeOfTheNullPointerDoesNothing() {
        // Task 0 has no host-allocated stack, so the engine frees pointer 0 on every plain
        // task end; that must not disturb the heap or the budget.
        ExecutionContext ctx = RuntimeWasm.memoryContext(2);
        MemoryBudget budget = new MemoryBudget(1 << 20);
        HostAllocator a = new HostAllocator(1024, budget);
        int p = a.realloc(ctx, 0, 0, 8, 32);
        long charged = budget.used();

        a.free(0, 0);

        assertEquals(charged, budget.used());
        assertEquals(p + 32, a.realloc(ctx, 0, 0, 8, 32));
    }
}
