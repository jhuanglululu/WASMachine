package com.jhuanglululu.wasmachine.runtime;

import static com.jhuanglululu.wasmachine.runtime.SyncWasm.BARRIER_NEW;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.KILL;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.RANDOM_DET;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.SIGNAL_NEW;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.SIGNAL_NOTIFY;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.WAIT;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.WAIT_ALL;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.WAIT_ANY;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jhuanglululu.wasmachine.runtime.SyncWasm.P;
import org.junit.jupiter.api.Test;

/**
 * Signals, barriers and and/or composites driven through real WASM. Every expectation is a
 * hand-traced log string: letters are logged by the task that reaches them, and tasks freed by
 * one release take their turns in spawn order, so the string pins down both <em>who</em> woke
 * and <em>in what order</em>.
 */
class SyncWaitableTest {

    private static final int A = 0;
    private static final int B = 1;
    private static final int C = 2;
    private static final int D = 3;
    private static final int E = 4;
    private static final int F = 5;

    @Test
    void barrierReleasesEveryArrivalTogetherInSpawnOrder() {
        // Park order is 0, 2, 1 (task 1 sleeps a tick first) but all three resume in spawn order.
        P main = new P()
                .i32(3).call(BARRIER_NEW).set(0)
                .child(0, new P().sleep(1).get(0).call(WAIT).log(B))
                .child(0, new P().get(0).call(WAIT).log(C))
                .get(0).call(WAIT).log(A)
                .sleep(10);

        assertEquals("ABC", SyncRun.run(main).assertFinished().trace());
    }

    @Test
    void orCancelledBarrierArrivalIsGivenBack() {
        // Task 1 parks on `barrier.or(signal)`, which counts as an arrival on the 2-arrival
        // barrier. The signal wins, so that arrival must be given back — otherwise task 0's own
        // wait would complete the barrier by itself and task 1's second wait would hang ("D").
        P main = new P()
                .i32(2).call(BARRIER_NEW).set(0)
                .call(SIGNAL_NEW).set(1)
                .get(0).get(1).call(WAIT_ANY).set(2)
                // The child needs two ids: the composite rides across as the spawn argument,
                // the barrier through the (shared) linear memory.
                .store(SyncWasm.VARS, 0)
                .child(2, new P().get(0).call(WAIT).log(B)
                        .load(SyncWasm.VARS).call(WAIT).log(D))
                .sleep(1)
                .get(1).i32(0).call(SIGNAL_NOTIFY).log(A)
                .get(0).call(WAIT).log(E)
                .sleep(5);

        assertEquals("ABED", SyncRun.run(main).assertFinished().trace());
    }

    @Test
    void waitAllLatchesLeavesThatFireWhileParked() {
        // s1 fires a whole tick before s2; the composite must remember it ("B" is reached).
        P main = new P()
                .call(SIGNAL_NEW).set(0)
                .call(SIGNAL_NEW).set(1)
                .get(0).get(1).call(WAIT_ALL).set(2)
                .child(2, new P().get(0).call(WAIT).log(B))
                .sleep(1)
                .get(0).i32(0).call(SIGNAL_NOTIFY).log(A)
                .sleep(1)
                .get(1).i32(0).call(SIGNAL_NOTIFY).log(C)
                .sleep(5);

        assertEquals("ACB", SyncRun.run(main).assertFinished().trace());
    }

    @Test
    void waitAnyReleasesOnEitherArm() {
        // Only the second arm ever fires.
        P main = new P()
                .call(SIGNAL_NEW).set(0)
                .call(SIGNAL_NEW).set(1)
                .get(0).get(1).call(WAIT_ANY).set(2)
                .child(2, new P().get(0).call(WAIT).log(B))
                .sleep(1)
                .get(1).i32(0).call(SIGNAL_NOTIFY).log(A)
                .sleep(5);

        assertEquals("AB", SyncRun.run(main).assertFinished().trace());
    }

    @Test
    void chainedCompositesFormATree() {
        // (s1 or s2) and s3: firing s2 and s3 is enough, s1 never fires.
        P main = new P()
                .call(SIGNAL_NEW).set(0)
                .call(SIGNAL_NEW).set(1)
                .call(SIGNAL_NEW).set(2)
                .get(0).get(1).call(WAIT_ANY).set(3)
                .get(3).get(2).call(WAIT_ALL).set(4)
                .child(4, new P().get(0).call(WAIT).log(B))
                .sleep(1)
                .get(2).i32(0).call(SIGNAL_NOTIFY).log(A)
                .sleep(1)
                .get(1).i32(0).call(SIGNAL_NOTIFY).log(C)
                .sleep(5);

        assertEquals("ACB", SyncRun.run(main).assertFinished().trace());
    }

    /**
     * Three waiters on one signal, parked at three different ticks: park order is task 3
     * (oldest), task 2, task 1 (newest), while spawn order is 1, 2, 3.
     */
    private static P notifyProgram(int mode, P beforeNotify) {
        return new P()
                .call(SIGNAL_NEW).set(0)
                .child(0, new P().sleep(2).get(0).call(WAIT).log(B))
                .child(0, new P().sleep(1).get(0).call(WAIT).log(C))
                .child(0, new P().get(0).call(WAIT).log(D))
                .sleep(3)
                .append(beforeNotify)
                .get(0).i32(mode).call(SIGNAL_NOTIFY).log(A)
                .sleep(5);
    }

    @Test
    void notifyAllReleasesEveryWaiterInSpawnOrder() {
        assertEquals("ABCD", SyncRun.run(notifyProgram(0, new P())).assertFinished().trace());
    }

    @Test
    void notifyOneOldestPicksTheFirstToPark() {
        assertEquals("AD", SyncRun.run(notifyProgram(1, new P())).assertFinished().trace());
    }

    @Test
    void notifyOneNewestPicksTheLastToPark() {
        assertEquals("AB", SyncRun.run(notifyProgram(2, new P())).assertFinished().trace());
    }

    @Test
    void notifyOneRandomDrawsFromTheSeededSchedulingStream() {
        // Scheduling stream = SplitMix64(instanceSeed ^ 0xD1B54A32D192ED03). With instance seed 1
        // that is SplitMix64(0xD1B54A32D192ED02), whose first output is 0x48416938EF0DCF6F;
        // 0x48416938EF0DCF6F mod 3 = 1, so the middle waiter by park order (task 2) wins.
        assertEquals("AC", SyncRun.seeded(notifyProgram(3, new P()), 1L).assertFinished().trace());
    }

    @Test
    void guestRandomDrawsDoNotDisturbTheSchedulingStream() {
        // Same program and seed, but the guest burns three deterministic draws first: the streams
        // are separate, so the same waiter still wins.
        P draws = new P().call(RANDOM_DET).drop().call(RANDOM_DET).drop().call(RANDOM_DET).drop();
        assertEquals("AC", SyncRun.seeded(notifyProgram(3, draws), 1L).assertFinished().trace());
    }

    /**
     * Task 1 parks on {@code sig.and(other)} — so a notify latches on it but cannot release it —
     * and task 2 parks on the bare signal. A {@code notify_one} that picks the composite waiter
     * would be swallowed (its latch is already set) and task 2 would starve, so selection has to
     * skip waiters this signal has already fired for.
     */
    private static P latchedCompositeProgram(int mode) {
        return new P()
                .call(SIGNAL_NEW).set(0)                                  // sig = 1
                .call(SIGNAL_NEW).set(1)                                  // other = 2
                .get(0).get(1).call(WAIT_ALL).set(2)                      // both = 3
                .child(2, new P().get(0).call(WAIT).log(B))                  // task 1 on the composite
                .child(0, new P().sleep(2).get(0).call(WAIT).log(C))         // task 2 on the bare signal
                // Tick 1: only the composite waiter exists, so this latches sig on task 1 alone.
                .sleep(1)
                .get(0).i32(0).call(SIGNAL_NOTIFY)
                // Tick 3: task 1 is latched and task 2 is now parked on the bare signal.
                .sleep(2)
                .get(0).i32(mode).call(SIGNAL_NOTIFY).log(A)
                .sleep(5);
    }

    @Test
    void notifyOneSkipsWaitersAlreadyLatchedOnThisSignal() {
        // All three one-waiter policies must find task 2; picking the latched composite would
        // silently drop the notify and hang the animation's only remaining waiter.
        for (int mode : new int[] {1, 2, 3}) {
            assertEquals("AC", SyncRun.seeded(latchedCompositeProgram(mode), 1L)
                    .assertFinished().trace(), "notify mode " + mode);
        }
    }

    @Test
    void notifyOneIsStillANoOpWhenEveryWaiterIsAlreadyLatched() {
        // Only the composite waiter exists and it is already latched: nothing to do, and above all
        // no crash and no double-fire.
        P main = new P()
                .call(SIGNAL_NEW).set(0)
                .call(SIGNAL_NEW).set(1)
                .get(0).get(1).call(WAIT_ALL).set(2)
                .child(2, new P().get(0).call(WAIT).log(B))
                .sleep(2)
                .get(0).i32(0).call(SIGNAL_NOTIFY)      // latch
                .get(0).i32(1).call(SIGNAL_NOTIFY)      // oldest: nobody eligible
                .get(0).i32(2).call(SIGNAL_NOTIFY)      // newest: nobody eligible
                .get(0).i32(3).call(SIGNAL_NOTIFY)      // random: nobody eligible
                .log(A)
                .sleep(1)
                .get(1).i32(0).call(SIGNAL_NOTIFY)      // the other arm finally releases it
                .log(4)
                .sleep(5);

        assertEquals("AEB", SyncRun.run(main).assertFinished().trace());
    }

    @Test
    void notifyWithNoWaitersIsANoOp() {
        // All four modes fire into the void before anyone waits; signals are edge-triggered, so
        // the later waiter must still park and only wake on the notify that follows it.
        P main = new P()
                .call(SIGNAL_NEW).set(0)
                .get(0).i32(0).call(SIGNAL_NOTIFY)
                .get(0).i32(1).call(SIGNAL_NOTIFY)
                .get(0).i32(2).call(SIGNAL_NOTIFY)
                .get(0).i32(3).call(SIGNAL_NOTIFY)
                .child(0, new P().get(0).call(WAIT).log(B))
                .sleep(1).log(A)
                .sleep(1).get(0).i32(0).call(SIGNAL_NOTIFY).log(C)
                .sleep(5);

        assertEquals("ACB", SyncRun.run(main).assertFinished().trace());
    }

    @Test
    void killingAParkedTaskGivesBackItsBarrierArrival() {
        // Task 1 arrives at the 2-arrival barrier and is then killed. Tasks 2 and 3 arrive on
        // ticks 2 and 3; the barrier must only complete on tick 3 (after "F"), which it can only
        // do if the dead task's arrival was given back.
        P main = new P()
                .i32(2).call(BARRIER_NEW).set(0)
                .childWithId(1, 0, new P().get(0).call(WAIT).log(B))
                .child(0, new P().sleep(2).get(0).call(WAIT).log(C))
                .child(0, new P().sleep(3).get(0).call(WAIT).log(D))
                .sleep(1)
                .get(1).call(KILL).log(A)
                .sleep(1).log(E)
                .sleep(1).log(F)
                .sleep(5);

        assertEquals("AEFCD", SyncRun.run(main).assertFinished().trace());
    }
}
