package com.jhuanglululu.wasmachine.runtime;

import static com.jhuanglululu.wasmachine.runtime.SyncWasm.REALLOC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jhuanglululu.wasm.Module;
import com.jhuanglululu.wasmachine.runtime.SyncWasm.P;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The live gauges and the on-demand capture window, through real guests.
 *
 * <p>Nothing is accumulated unless a capture is armed, so a window's numbers cannot be checked
 * against a standing total. They are anchored two other ways instead: against the interpreter's
 * own fuel accounting, which is an entirely separate mechanism from sampling, and against the
 * same three ticks measured in a different shape (three one-tick captures instead of one
 * three-tick capture). The guests are straight-line and deterministic, so both anchors are exact.
 */
class StatsTest {

    private static final long BUDGET = 10_000_000L;

    private static MachineInstance instance(P main) {
        return new MachineInstance(Module.parse(SyncWasm.module(main)),
                new MachineInstance.Config("stats", RuntimeWasm.ENGINE_MODULE, "_engine_main",
                        List.of(SyncRun.ENGINE_ABI), 1 << 20, 0L),
                (name, message) -> { }, Map.of());
    }

    /** {@code n} host calls worth of work, so one tick can be made visibly dearer than another. */
    private static P busy(int n) {
        P p = new P();
        for (int i = 0; i < n; i++) {
            p.log(0);
        }
        return p;
    }

    /** Three ticks of deliberately unequal work: one log, then thirty, then one again. */
    private static P unevenWork() {
        return new P()
                .log(0).sleep(1)
                .append(busy(30)).sleep(1)
                .log(2).sleep(1)
                .log(3);
    }

    @Test
    void aSampleMatchesTheInterpretersOwnFuelAccounting() {
        // The anchor: fuel exhaustion is decided inside the dispatch loop, with no reference to
        // the sampler. If a one-tick window says the tick cost N instructions, then N fuel must
        // be exactly enough to reach the guest's first blocking point — and N-1 must not be.
        MachineInstance measured = instance(unevenWork());
        assertTrue(measured.startCapture(1));
        assertInstanceOf(TickResult.Running.class, measured.tick(0, BUDGET));
        long n = measured.captureResult().orElseThrow().instructionsSum();
        assertTrue(n > 0);

        assertInstanceOf(TickResult.Running.class, instance(unevenWork()).tick(0, n),
                "exactly the sampled instruction count must reach the sleep");
        TickResult starved = instance(unevenWork()).tick(0, n - 1);
        assertInstanceOf(TickResult.Errored.class, starved,
                "one instruction less must not, or the sample over-counted");
        assertTrue(((TickResult.Errored) starved).message().contains("budget"));
    }

    @Test
    void aWindowAggregatesExactlyTheTicksItCovers() {
        // One three-tick window, then the same three ticks measured as three one-tick windows:
        // a differently shaped measurement of identical work, so the aggregates must agree.
        MachineInstance whole = instance(unevenWork());
        assertTrue(whole.startCapture(3));
        assertEquals(3, whole.captureRemainingTicks());
        for (long t = 0; t < 3; t++) {
            assertInstanceOf(TickResult.Running.class, whole.tick(t, BUDGET));
            if (t == 0) {
                // A second admin measuring the same instance must not restart the window.
                assertFalse(whole.startCapture(50), "a capture is already armed");
                assertEquals(2, whole.captureRemainingTicks(), "the running window is untouched");
            }
        }
        MachineInstance.CaptureSummary window = whole.captureResult().orElseThrow();

        MachineInstance perTick = instance(unevenWork());
        long sum = 0;
        long min = Long.MAX_VALUE;
        long max = 0;
        for (long t = 0; t < 3; t++) {
            assertTrue(perTick.startCapture(1));
            assertInstanceOf(TickResult.Running.class, perTick.tick(t, BUDGET));
            long one = perTick.captureResult().orElseThrow().instructionsSum();
            sum += one;
            min = Math.min(min, one);
            max = Math.max(max, one);
        }

        assertEquals(3, window.ticksCaptured());
        assertTrue(window.complete(), "it ran the full length it was armed for");
        assertEquals(0, whole.captureRemainingTicks(), "the window closed on its last tick");
        assertEquals(sum, window.instructionsSum());
        assertEquals(min, window.instructionsMin());
        assertEquals(max, window.instructionsMax());
        assertTrue(window.instructionsMin() < window.instructionsMax(),
                "the middle tick did thirty times the host calls: " + window);
        assertEquals((double) sum / 3, window.meanInstructions());
    }

    @Test
    void theWindowRemembersMemoryTheGaugeNoLongerShows() {
        // Allocate, hold across a tick boundary, then free. The gauge reads nothing afterwards,
        // so the window's sampled peak is the only remaining evidence the run ever held
        // anything — which is the whole reason the window records one.
        int size = 4096;
        P main = new P()
                .i32(0).i32(0).i32(8).i32(size).call(REALLOC).set(0)
                .sleep(1)
                .get(0).i32(size).i32(8).i32(0).call(REALLOC).drop();
        MachineInstance inst = instance(main);

        assertTrue(inst.startCapture(2));
        assertInstanceOf(TickResult.Running.class, inst.tick(0, BUDGET));
        MachineInstance.StatsSnapshot held = inst.stats();
        assertTrue(held.memoryUsedBytes() >= size,
                "the heap should be charged while held, was " + held.memoryUsedBytes());
        assertEquals(1 << 20, held.memoryCapBytes());
        assertEquals(1, held.liveTasks());
        assertEquals(0, held.totalSpawns());

        assertInstanceOf(TickResult.Finished.class, inst.tick(1, BUDGET));
        assertEquals(0, inst.stats().memoryUsedBytes(), "the free gave every byte back");

        MachineInstance.CaptureSummary window = inst.captureResult().orElseThrow();
        assertEquals(2, window.ticksCaptured());
        assertEquals(held.memoryUsedBytes(), window.memoryPeakBytes(),
                "the window kept the reading the gauge has since lost");
        assertTrue(window.memoryPeakBytes() >= size);
    }

    @Test
    void stopCaptureKeepsWhatItSawAndClosesTheWindow() {
        MachineInstance inst = instance(unevenWork());

        assertTrue(inst.startCapture(50));
        for (long t = 0; t < 3; t++) {
            assertInstanceOf(TickResult.Running.class, inst.tick(t, BUDGET));
        }
        assertTrue(inst.stopCapture());

        MachineInstance.CaptureSummary window = inst.captureResult().orElseThrow();
        assertEquals(3, window.ticksCaptured(), "every sample taken so far is kept");
        assertFalse(window.complete(), "47 of the 50 ticks never happened");
        assertTrue(window.instructionsSum() > 0);
        assertEquals(0, inst.captureRemainingTicks());

        // The window is closed, so ticking on adds nothing to it — not even the tick that ends
        // the run, which would otherwise have been the sample that closed it.
        assertInstanceOf(TickResult.Finished.class, inst.tick(3, BUDGET));
        assertEquals(window, inst.captureResult().orElseThrow());
        assertFalse(inst.stopCapture(), "nothing left to stop");
    }

    @Test
    void anInstanceThatEndsInsideTheWindowReportsWhatItSaw() {
        MachineInstance inst = instance(new P().log(0)); // returns on its first tick

        assertTrue(inst.startCapture(5));
        assertInstanceOf(TickResult.Finished.class, inst.tick(0, BUDGET));

        MachineInstance.CaptureSummary window = inst.captureResult().orElseThrow();
        assertEquals(1, window.ticksCaptured(), "the tick that ended the run still counted");
        assertFalse(window.complete(), "four of the five ticks can never happen");
        assertTrue(window.instructionsSum() > 0);
        assertEquals(window.instructionsMin(), window.instructionsMax(), "one sample");
        assertEquals(0, inst.captureRemainingTicks());

        // Polling a dead instance is not a tick: it adds no sample.
        assertInstanceOf(TickResult.Finished.class, inst.tick(1, BUDGET));
        assertEquals(window, inst.captureResult().orElseThrow());

        // And arming a fresh window on the corpse closes it at once rather than waiting on a
        // tick that can never come.
        assertTrue(inst.startCapture(5));
        MachineInstance.CaptureSummary nothing = inst.captureResult().orElseThrow();
        assertEquals(0, nothing.ticksCaptured());
        assertFalse(nothing.complete());
        assertEquals(0, nothing.instructionsMin(), "an empty window reports zeroes, not sentinels");
        assertEquals(0, inst.captureRemainingTicks());
    }
}
