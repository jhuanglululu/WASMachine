package com.jhuanglululu.wasmachine.runtime;

import static com.jhuanglululu.wasmachine.runtime.SyncWasm.REALLOC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jhuanglululu.wasm.Module;
import com.jhuanglululu.wasmachine.runtime.SyncWasm.P;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Run totals and the on-demand capture window, through real guests.
 *
 * <p>What is actually worth pinning here is the arithmetic nobody can verify by reading: that a
 * window's samples add up to the same instructions the independent run total moved by, that the
 * memory watermark survives the free that ends it, and that an instance dying inside a window
 * reports what it saw instead of a full-length lie.
 */
class StatsTest {

    private static final long BUDGET = 10_000_000L;

    private static MachineInstance instance(P main) {
        return instance(main, 1 << 20);
    }

    private static MachineInstance instance(P main, long memoryCap) {
        return new MachineInstance(Module.parse(SyncWasm.module(main)),
                new MachineInstance.Config("stats", RuntimeWasm.ENGINE_MODULE, "_engine_main",
                        List.of(SyncRun.ENGINE_ABI), memoryCap, 0L),
                (name, message) -> { }, SyncWasm.stubPluginImports());
    }

    /** {@code n} host calls worth of work, so one tick can be made visibly dearer than another. */
    private static P busy(int n) {
        P p = new P();
        for (int i = 0; i < n; i++) {
            p.log(0);
        }
        return p;
    }

    @Test
    void aCaptureWindowAddsUpToTheRunTotalItSpanned() {
        // Three ticks of deliberately unequal work: one log, then thirty, then one again.
        P main = new P()
                .log(0).sleep(1)
                .append(busy(30)).sleep(1)
                .log(2).sleep(1)
                .log(3);
        MachineInstance inst = instance(main);

        MachineInstance.StatsSnapshot before = inst.stats();
        assertEquals(0, before.lastTickInstructions(), "nothing has run yet");
        assertTrue(inst.startCapture(3));
        assertEquals(3, inst.captureRemainingTicks());

        for (long t = 0; t < 3; t++) {
            assertInstanceOf(TickResult.Running.class, inst.tick(t, BUDGET));
            if (t == 0) {
                // A second admin measuring the same instance must not restart the window.
                assertFalse(inst.startCapture(50), "a capture is already armed");
                assertEquals(2, inst.captureRemainingTicks(), "the running window is untouched");
            }
        }

        MachineInstance.StatsSnapshot after = inst.stats();
        MachineInstance.CaptureSummary window = inst.captureResult().orElseThrow();

        assertEquals(0, inst.captureRemainingTicks(), "the window closed on its last tick");
        assertEquals(3, window.ticksCaptured());
        assertTrue(window.complete(), "it ran the full length it was armed for");
        // The round trip: the samples must account for exactly the instructions the independent
        // run counter moved by over the same three ticks.
        assertEquals(after.totalInstructions() - before.totalInstructions(),
                window.instructionsSum());
        assertTrue(window.instructionsMin() < window.instructionsMax(),
                "the middle tick did thirty times the host calls: " + window);
        assertEquals(after.lastTickInstructions(), inst.stats().lastTickInstructions());
        assertEquals(3, after.uptimeTicks());
        assertEquals(1, after.liveTasks());
        assertEquals(0, after.totalForks());
    }

    @Test
    void theMemoryPeakOutlivesTheAllocationThatSetIt() {
        // Allocate, hold across a tick boundary, then free: `used` comes back down, the watermark
        // does not — which is the whole reason the watermark exists, since a sampler that only
        // reads `used` at tick ends would report this run as having used nothing.
        int size = 4096;
        P main = new P()
                .i32(0).i32(0).i32(8).i32(size).call(REALLOC).set(0)
                .sleep(1)
                .get(0).i32(size).i32(8).i32(0).call(REALLOC).drop();
        MachineInstance inst = instance(main);

        assertInstanceOf(TickResult.Running.class, inst.tick(0, BUDGET));
        MachineInstance.StatsSnapshot held = inst.stats();
        assertTrue(held.memoryUsedBytes() >= size,
                "the heap should be charged while held, was " + held.memoryUsedBytes());
        assertTrue(held.memoryPeakBytes() >= held.memoryUsedBytes());
        assertEquals(1 << 20, held.memoryCapBytes());

        assertInstanceOf(TickResult.Finished.class, inst.tick(1, BUDGET));
        MachineInstance.StatsSnapshot freed = inst.stats();
        assertEquals(0, freed.memoryUsedBytes(), "the free gave every byte back");
        assertEquals(held.memoryPeakBytes(), freed.memoryPeakBytes(), "but the peak stands");
        assertTrue(freed.memoryPeakBytes() >= size);
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

        // Polling a dead instance is not a tick: it adds no sample and moves no total.
        long uptime = inst.stats().uptimeTicks();
        assertInstanceOf(TickResult.Finished.class, inst.tick(1, BUDGET));
        assertEquals(window, inst.captureResult().orElseThrow());
        assertEquals(uptime, inst.stats().uptimeTicks());

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
