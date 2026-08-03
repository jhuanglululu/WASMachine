package com.jhuanglululu.wasmachine.runtime;

import static com.jhuanglululu.wasmachine.runtime.SyncWasm.STACK_POINTER_GLOBAL;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.VARS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jhuanglululu.wasmachine.runtime.SyncWasm.P;
import org.junit.jupiter.api.Test;

/**
 * The engine-ABI-2 memory model, exercised through real guests: one linear memory shared by
 * every task, one heap, and a private shadow stack per task.
 *
 * <p>Every expectation is a hand-traced log string. A letter is only logged when a task reads
 * back a value some <em>other</em> task wrote (or, for the stack cases, a value only its own
 * stack could still hold), so a missing letter is the failure — sharing that silently did not
 * happen cannot pass.
 */
class SharedMemoryTest {

    private static final int A = 0;
    private static final int B = 1;
    private static final int C = 2;
    private static final int D = 3;

    @Test
    void storesTravelBothWaysBetweenTasks() {
        // Main writes 3 before spawning; the child must see it. The child then writes 9, which
        // main must see after it wakes. Under the old fork semantics neither store would cross.
        P child = new P()
                .load(VARS + 4).ifEq(3, new P().log(B))
                .i32(VARS).i32(9).storeTop();
        P main = new P()
                .i32(VARS + 4).i32(3).storeTop()
                .child(0, child)
                .sleep(1)
                .load(VARS).ifEq(9, new P().log(A))
                .sleep(1);

        assertEquals("BA", SyncRun.run(main).assertFinished().trace());
    }

    @Test
    void memoryGrowByOneTaskIsVisibleToAnother() {
        // The fixture module declares one page (65536 bytes) with the heap at 1024. Spawning
        // already grows it: the child's 64 KiB stack ends at 66560, so two pages are needed —
        // a host-side grow the spawner sees straight away. The child then grows it once more,
        // and main must see three pages and be able to address the last one.
        P child = new P().i32(1).memoryGrow().drop();
        P main = new P()
                .child(0, child)
                .memorySize().ifEq(2, new P().log(A))   // grown by the spawn's stack allocation
                .sleep(1)
                .memorySize().ifEq(3, new P().log(B))   // grown again by the child
                .i32(140000).i32(42).storeTop()
                .load(140000).ifEq(42, new P().log(C))  // the new page is really addressable
                .sleep(1);

        assertEquals("ABC", SyncRun.run(main).assertFinished().trace());
    }

    /**
     * A task that pushes a frame onto its own shadow stack: it drops {@code __stack_pointer}
     * by 16, writes {@code mark} there, sleeps so the other task gets a turn, then reads its
     * slot back. It also records the stack pointer it was given at {@code slot}, so main can
     * check the two regions afterwards.
     */
    private static P stackUser(int mark, int letter, int slot) {
        return new P()
                .globalGet(STACK_POINTER_GLOBAL).i32(16).sub().globalSet(STACK_POINTER_GLOBAL)
                .i32(slot).globalGet(STACK_POINTER_GLOBAL).storeTop()
                .globalGet(STACK_POINTER_GLOBAL).i32(mark).storeTop()
                .sleep(1)
                .globalGet(STACK_POINTER_GLOBAL).loadTop().ifEq(mark, new P().log(letter));
    }

    @Test
    void eachTaskGetsItsOwnStackRegion() {
        // Two tasks write to the same offset below their own stack pointer. If they shared a
        // stack they would be writing to one address and the first would read the other's mark
        // back, so only one letter would ever appear.
        P main = new P()
                .child(0, stackUser(11, A, VARS))
                .child(0, stackUser(22, B, VARS + 4))
                .sleep(2)
                // The two stack pointers must be different addresses...
                .load(VARS).load(VARS + 4).raw(0x47).ifEq(1, new P().log(C))
                // ...and both 16-byte aligned, as the C ABI on wasm requires.
                .load(VARS).i32(15).raw(0x71)
                .ifEq(0, new P().load(VARS + 4).i32(15).raw(0x71).ifEq(0, new P().log(D)))
                .sleep(1);

        assertEquals("ABCD", SyncRun.run(main).assertFinished().trace());
    }

    @Test
    void aSpawnedTaskCanSpawnAndTheGrandchildStillSharesTheMemory() {
        // The grandchild writes; main — which never met it — reads the value back.
        P grandchild = new P().i32(VARS).i32(77).storeTop().log(C);
        P child = new P().child(0, grandchild).log(B);
        P main = new P()
                .child(0, child)
                .sleep(2)
                .load(VARS).ifEq(77, new P().log(A))
                .sleep(1);

        assertEquals("BCA", SyncRun.run(main).assertFinished().trace());
    }

    @Test
    void spawnReturnsTheChildIdWithoutYieldingTheSpawnersTurn() {
        // Both spawns and both id checks happen in one uninterrupted turn: if spawn suspended
        // the spawner, a child would slip in and the trace would not start with "AB".
        P main = new P()
                .childWithId(0, 0, new P().log(C))
                .childWithId(1, 0, new P().log(D))
                .get(0).ifEq(1, new P().log(A))   // first child is task 1
                .get(1).ifEq(2, new P().log(B))   // second is task 2
                .sleep(2);

        assertEquals("ABCD", SyncRun.run(main).assertFinished().trace());
    }
}
