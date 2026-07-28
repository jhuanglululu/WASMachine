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
    void copyIsIndependent() {
        ExecutionContext ctx = RuntimeWasm.memoryContext(2);
        HostAllocator a = new HostAllocator(1024, 1 << 20);
        int p1 = a.realloc(ctx, 0, 0, 8, 32);
        HostAllocator b = a.copy();
        // Allocations from the two allocators must not overlap the still-live p1, and the
        // fork's bookkeeping is independent: freeing in one does not affect the other.
        int p2 = b.realloc(ctx, 0, 0, 8, 32);
        assertEquals(p1 + 32, p2); // b continued a's bump top
        a.realloc(ctx, p1, 32, 8, 0); // free in a only
        int p3 = b.realloc(ctx, 0, 0, 8, 32); // b does not see a's freed block
        assertTrue(p3 != p1);
    }
}
