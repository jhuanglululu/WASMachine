package com.jhuanglululu.wasmachine.runtime;

import com.jhuanglululu.wasm.ExecutionContext;
import java.util.Map;
import java.util.TreeMap;

/**
 * The host side of the guest's {@code #[global_allocator]}: it implements the
 * {@code realloc(ptr, old_size, align, new_size)} ABI import over the instance's linear
 * memory, so no {@code dlmalloc} is compiled into the animation.
 *
 * <p><b>One allocator per instance</b>, not per task: since engine ABI 2 every task of an
 * instance shares one linear memory, so there is one heap and one free list. A pointer
 * allocated by any task is valid in all of them, and the instance's memory charge is simply
 * this heap's high-water mark — no fork copy to charge twice.
 *
 * <p>It is a real free-list allocator with coalescing (not a bump pointer): freed
 * blocks are merged with adjacent free blocks and reused, and a free block that reaches
 * the top of the heap lowers the bump pointer. Allocations honor the requested
 * alignment. The heap starts at the module's exported {@code __heap_base} and grows
 * upward, growing linear memory through the {@link ExecutionContext} as needed, but never past the
 * per-instance {@link MemoryBudget} — which it shares with the channel buffers, because the
 * configured cap is per instance and covers everything the instance holds.
 *
 * <p>The guest always supplies {@code old_size} on free/realloc, so blocks carry no
 * header — the free list is the only bookkeeping.
 *
 * <p>Not thread-safe: one animation instance runs one task at a time, so no
 * synchronization is needed.
 */
public final class HostAllocator {

    private static final int PAGE = 65536;

    private final int heapBase;
    private final MemoryBudget budget;
    private int top; // next fresh address (bump pointer above all allocations)
    // Free blocks: start offset -> size in bytes, sorted by offset for coalescing.
    private final TreeMap<Integer, Integer> free = new TreeMap<>();

    /**
     * @param heapBase the module's {@code __heap_base}; the heap starts here
     * @param budget   the instance-wide byte budget this heap charges against
     */
    public HostAllocator(int heapBase, MemoryBudget budget) {
        this.heapBase = heapBase;
        this.budget = budget;
        this.top = heapBase;
    }

    /** Convenience for callers with no shared budget (tests): a private cap of {@code byteCap}. */
    public HostAllocator(int heapBase, long byteCap) {
        this(heapBase, new MemoryBudget(byteCap));
    }

    /**
     * Frees a block the host itself allocated (a task's stack region), with the same
     * bookkeeping a guest {@code realloc(ptr, size, _, 0)} would do. Separate from
     * {@link #realloc} only because the host has no {@link ExecutionContext} to hand it and
     * needs none: freeing never touches linear memory.
     */
    public void free(int ptr, int size) {
        if (ptr != 0) {
            freeBlock(ptr, size);
        }
    }

    /**
     * The guest ABI entry point. {@code alloc = realloc(0, 0, align, size)};
     * {@code free = realloc(ptr, old, align, 0)}; resize otherwise.
     *
     * @return the (possibly new) pointer, or {@code 0} for a free / zero-size request
     * @throws MemoryCapExceededException if the heap would exceed the byte cap
     */
    public int realloc(ExecutionContext ctx, int ptr, int oldSize, int align, int newSize) {
        if (ptr == 0) {
            return newSize == 0 ? 0 : alloc(ctx, newSize, align);
        }
        if (newSize == 0) {
            freeBlock(ptr, oldSize);
            return 0;
        }
        if (newSize == oldSize) {
            return ptr;
        }
        int fresh = alloc(ctx, newSize, align);
        int keep = Math.min(oldSize, newSize);
        if (keep > 0) {
            ctx.writeBytes(fresh, ctx.readBytes(ptr, keep));
        }
        freeBlock(ptr, oldSize);
        return fresh;
    }

    private int alloc(ExecutionContext ctx, int size, int align) {
        int need = Math.max(size, 1);
        int a = Math.max(align, 1);

        // First-fit over the free list, honoring alignment.
        Integer chosenOff = null;
        int chosenSize = 0;
        int chosenAligned = 0;
        for (Map.Entry<Integer, Integer> e : free.entrySet()) {
            int off = e.getKey();
            int bsize = e.getValue();
            int aligned = alignUp(off, a);
            if ((long) aligned + need <= (long) off + bsize) {
                chosenOff = off;
                chosenSize = bsize;
                chosenAligned = aligned;
                break;
            }
        }
        if (chosenOff != null) {
            free.remove(chosenOff);
            if (chosenAligned > chosenOff) {
                addFree(chosenOff, chosenAligned - chosenOff); // alignment padding is reusable
            }
            int tailStart = chosenAligned + need;
            int tailEnd = chosenOff + chosenSize;
            if (tailEnd > tailStart) {
                addFree(tailStart, tailEnd - tailStart);
            }
            return chosenAligned;
        }

        // Otherwise bump the top, charging the instance budget for the growth.
        int aligned = alignUp(top, a);
        long newTop = (long) aligned + need;
        budget.reserve(newTop - top, "the guest heap");
        ensureCapacity(ctx, newTop);
        if (aligned > top) {
            addFree(top, aligned - top);
        }
        top = (int) newTop;
        return aligned;
    }

    private void freeBlock(int ptr, int oldSize) {
        addFree(ptr, Math.max(oldSize, 1));
    }

    /** Inserts a free block, coalescing with an immediately-adjacent block on each side. */
    private void addFree(int off, int size) {
        int start = off;
        int len = size;

        Map.Entry<Integer, Integer> lower = free.lowerEntry(start);
        if (lower != null && lower.getKey() + lower.getValue() == start) {
            start = lower.getKey();
            len += lower.getValue();
            free.remove(lower.getKey());
        }
        Integer nextSize = free.get(start + len);
        if (nextSize != null) {
            free.remove(start + len);
            len += nextSize;
        }
        if (start + len == top) {
            // Adjacent to the bump top: return it to the fresh region, and to the budget.
            budget.release(top - start);
            top = start;
            return;
        }
        free.put(start, len);
    }

    private void ensureCapacity(ExecutionContext ctx, long requiredBytes) {
        while (ctx.memorySize() < requiredBytes) {
            long deficit = requiredBytes - ctx.memorySize();
            int pages = (int) ((deficit + PAGE - 1) / PAGE);
            if (ctx.growMemory(pages) < 0) {
                throw new MemoryCapExceededException("cannot grow linear memory to "
                        + requiredBytes + " bytes");
            }
        }
    }

    private static int alignUp(int value, int align) {
        // align is a power of two in practice, but this form is correct for any align >= 1.
        return (int) (((long) value + align - 1) / align * align);
    }

    /** The current heap high-water mark (bytes above {@code __heap_base}). */
    public long allocatedHighWater() {
        return (long) top - heapBase;
    }
}
