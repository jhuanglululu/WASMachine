package com.jhuanglululu.wasmachine.runtime;

import static com.jhuanglululu.wasmachine.runtime.SyncWasm.LOG;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.SCRATCH;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.SHARED_ALLOC;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jhuanglululu.wasm.Module;
import com.jhuanglululu.wasmachine.runtime.SyncWasm.P;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The shared static region, exercised through real guests: one instance-wide buffer addressed
 * from {@code SHARED_BASE} up, which a fork references instead of copying.
 *
 * <p>Every expectation is a hand-traced log string. A letter is only logged when a task reads
 * back a value some <em>other</em> task wrote (or when a private write provably did <em>not</em>
 * cross a fork), so a missing letter is the failure — sharing that silently did not happen
 * cannot pass, and neither can sharing that silently happened where it must not.
 */
class SharedRegionTest {

    private static final int A = 0;
    private static final int B = 1;
    private static final int C = 2;
    private static final int D = 3;

    private static final long BUDGET = 10_000_000L;

    private static MachineInstance instance(P main, long memoryCap, long sharedCap,
            List<String> logs) {
        return new MachineInstance(Module.parse(SyncWasm.module(main)),
                new MachineInstance.Config("shared", RuntimeWasm.ENGINE_MODULE, "_engine_main",
                        List.of(SyncRun.ENGINE_ABI), memoryCap, 0L, Map.of(), sharedCap),
                (name, message) -> logs.add(message), Map.of());
    }

    private static SyncRun.Result run(P main, long memoryCap, long sharedCap) {
        List<String> logs = new ArrayList<>();
        MachineInstance inst = instance(main, memoryCap, sharedCap, logs);
        return SyncRun.drive((tick, fuel) -> SyncRun.outcomeOf(inst.tick(tick, fuel)),
                logs, 10, BUDGET);
    }

    private static SyncRun.Result run(P main) {
        return run(main, 1 << 20, 1 << 20);
    }

    /** {@code local0 = shared_alloc(size, align)}. */
    private static P alloc(int size, int align) {
        return new P().i32(size).i32(align).call(SHARED_ALLOC).set(0);
    }

    // --- routing ---

    @Test
    void sharedStoresTravelBothWaysAcrossAFork() {
        // The pointer itself rides across in a local (a fork copies locals); what does not get
        // copied is what it points at, so both stores land in the one buffer.
        P child = new P()
                .get(0).loadTop().ifEq(7, new P().log(B))
                .get(0).storeConst(9);
        P main = new P().append(alloc(16, 4))
                .get(0).storeConst(7)
                .child(child)
                .sleep(1)
                .get(0).loadTop().ifEq(9, new P().log(A))
                .sleep(1);

        assertEquals("BA", run(main).assertFinished().trace());
    }

    @Test
    void privateStoresStillDoNotCrossAFork() {
        // The same shape at a private address: the child sees the value the parent wrote before
        // forking, but its own write stays in its own copy — fork semantics are untouched.
        P child = new P()
                .load(SCRATCH).ifEq(3, new P().log(B))
                .i32(SCRATCH).storeConst(9);
        P main = new P()
                .i32(SCRATCH).storeConst(3)
                .child(child)
                .sleep(1)
                .load(SCRATCH).ifEq(3, new P().log(A))
                .sleep(1);

        assertEquals("BA", run(main).assertFinished().trace());
    }

    @Test
    void aSharedAddressIsNegativeAsAnI32AndHonorsTheRequestedAlignment() {
        P main = new P()
                .i32(1).i32(1).call(SHARED_ALLOC).set(0)   // 1 byte at offset 0
                .i32(4).i32(16).call(SHARED_ALLOC).set(1)  // aligned up to offset 16
                .get(1).i32(15).and().ifEq(0, new P().log(A))
                .get(1).get(0).raw(0x4B).ifEq(1, new P().log(B))  // i32.gt_u: bumps upward
                .get(0).i32(0).raw(0x48).ifEq(1, new P().log(C))  // i32.lt_s: the routing bit
                .sleep(1);

        assertEquals("ABC", run(main).assertFinished().trace());
    }

    @Test
    void aHostReadOfASharedAddressRoutesThereToo() {
        // memory.copy carries the letter table into the region, then `log` — which reads guest
        // memory host-side — must find it there.
        P main = new P().append(alloc(16, 4))
                .i32(8).set(1)
                .memoryCopy(new P().get(0), new P().i32(0), 8)
                .get(0).get(1).call(LOG)
                .sleep(1);

        assertEquals("ABCDEFGH", run(main).assertFinished().trace());
    }

    // --- bounds ---

    @Test
    void aLoadPastTheEndOfTheRegionTraps() {
        // The region is exactly as big as what shared_alloc handed out: 16 bytes, so the four
        // bytes at +16 are outside it.
        P main = new P().append(alloc(16, 4))
                .get(0).i32(16).add().loadTop().drop()
                .sleep(1);

        run(main).assertKilled("out of bounds memory access");
    }

    @Test
    void aBulkCopyThatWouldCrossOutOfTheRegionTraps() {
        // Source and destination are each routed on their own, so a private -> shared copy is
        // fine; what is not fine is a length that runs off the end of the region it landed in.
        P main = new P().append(alloc(16, 4))
                .memoryCopy(new P().get(0), new P().i32(0), 32)
                .sleep(1);

        run(main).assertKilled("out of bounds memory access");
    }

    @Test
    void aBulkFillThatWouldCrossOutOfTheRegionTraps() {
        P main = new P().append(alloc(16, 4))
                .memoryFill(new P().get(0), 0xAB, 17)
                .sleep(1);

        run(main).assertKilled("out of bounds memory access");
    }

    @Test
    void aPrivateBulkCopyCannotReachTheRegionByRunningOffTheTop() {
        // The private memory ends decades of addresses below SHARED_BASE, so "crossing the
        // boundary" is never a copy that spills into the region — it is a plain out-of-bounds.
        P main = new P()
                .memoryCopy(new P().i32(65530), new P().i32(0), 16)
                .sleep(1);

        run(main).assertKilled("out of bounds memory access");
    }

    // --- grow stays private ---

    @Test
    void memoryGrowAndMemorySizeIgnoreTheSharedRegion() {
        // The fixture module declares one page. shared_alloc adds 4 KiB of addressable memory
        // and memory.size must not notice; memory.grow then behaves exactly as it always did.
        P main = new P()
                .memorySize().ifEq(1, new P().log(A))
                .i32(4096).i32(1).call(SHARED_ALLOC).drop()
                .memorySize().ifEq(1, new P().log(B))
                .i32(1).memoryGrow().ifEq(1, new P().log(C))  // returns the old page count
                .memorySize().ifEq(2, new P().log(D))
                .sleep(1);

        assertEquals("ABCD", run(main).assertFinished().trace());
    }

    // --- accounting ---

    @Test
    void theRegionChargesTheMemoryBudgetOnceHoweverManyTasksAddressIt() {
        // Three forks all address the same 4096 bytes. Nothing is on the heap, so a fork's own
        // charge is zero and the whole reading is the region — once, not four times.
        P main = new P()
                .i32(4096).i32(1).call(SHARED_ALLOC).drop()
                .child(new P().sleep(5))
                .child(new P().sleep(5))
                .child(new P().sleep(5))
                .sleep(5);

        List<String> logs = new ArrayList<>();
        MachineInstance inst = instance(main, 1 << 20, 1 << 20, logs);
        inst.tick(0, BUDGET);

        assertEquals(4, inst.stats().liveTasks(), "all four tasks should be alive and sleeping");
        assertEquals(4096, inst.stats().memoryUsedBytes());
    }

    @Test
    void exhaustingTheRegionCapFailsTheInstance() {
        P main = new P().i32(4096).i32(1).call(SHARED_ALLOC).drop().sleep(1);

        run(main, 1 << 20, 1024).assertKilled("shared static region cap of 1024");
    }

    @Test
    void aRegionThatFitsItsOwnCapStillAnswersToTheInstanceMemoryCap() {
        // Two ceilings, both real: the region cap bounds the region, the memory cap bounds the
        // instance. Charging as it grows is what makes the second one bite.
        P main = new P().i32(4096).i32(1).call(SHARED_ALLOC).drop().sleep(1);

        run(main, 1024, 1 << 20).assertKilled("animation memory cap of 1024");
    }
}
