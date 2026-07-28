package com.jhuanglululu.wasmachine.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WorkerPoolSizerTest {

    @Test
    void targetFormulaClampsToOneAndTheMinimumOfDemandCoresAndCap() {
        WorkerPoolSizer s = new WorkerPoolSizer(4, 8, 200);
        assertEquals(1, s.target(0));   // max(1, ...)
        assertEquals(1, s.target(4));   // 4/5 = 0 -> clamped to 1
        assertEquals(2, s.target(10));  // 10/5 = 2
        assertEquals(4, s.target(100)); // 20 demand, but capped at maxThreads 4
    }

    @Test
    void targetCappedByCores() {
        WorkerPoolSizer s = new WorkerPoolSizer(16, 2, 200);
        assertEquals(2, s.target(100)); // demand 20, cap 16, but only 2 cores
    }

    @Test
    void growsImmediatelyButShrinksOnlyAfterDelay() {
        WorkerPoolSizer s = new WorkerPoolSizer(8, 8, 100);
        assertEquals(1, s.current());

        // Demand jumps to 20 instances -> target 4; grows at once.
        assertEquals(4, s.update(20, 0));
        assertEquals(4, s.current());

        // Demand drops to 5 instances -> target 1; must NOT shrink yet.
        assertEquals(4, s.update(5, 1));
        assertEquals(4, s.update(5, 99));   // 99 - 1 = 98 < 100
        // Delay elapsed.
        assertEquals(1, s.update(5, 101));  // 101 - 1 = 100 >= 100 -> shrink
    }

    @Test
    void demandRecoveringBeforeDelayCancelsTheShrink() {
        WorkerPoolSizer s = new WorkerPoolSizer(8, 8, 100);
        s.update(20, 0);                    // grow to 4
        assertEquals(4, s.update(5, 10));   // arm shrink at tick 10
        assertEquals(4, s.update(20, 50));  // demand back up: stays 4, disarmed
        // Even long after the original arm tick, no shrink happens.
        assertEquals(4, s.update(20, 500));
    }

    @Test
    void growWhileShrinkArmedGrowsImmediatelyAndDisarms() {
        WorkerPoolSizer s = new WorkerPoolSizer(8, 8, 100);
        s.update(40, 0);                    // grow to 8
        assertEquals(8, s.update(5, 10));   // arm shrink toward 1
        assertEquals(8, s.update(40, 20));  // demand high again -> stays 8, disarmed
        assertEquals(8, s.update(40, 200)); // no delayed shrink fires
    }
}
