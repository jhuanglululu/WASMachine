package com.jhuanglululu.wasmachine.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.jhuanglululu.wasm.Module;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Determinism: tasks woken the same tick resume in spawn order. */
class DeterminismTest {

    @Test
    void tasksWokenSameTickRunInSpawnOrder() {
        List<String> logs = new ArrayList<>();
        MachineInstance inst = new MachineInstance(
                Module.parse(RuntimeWasm.forkDeterminismModule()),
                new MachineInstance.Config("det", RuntimeWasm.ENGINE_MODULE, "_engine_main",
                        List.of(SyncRun.ENGINE_ABI), 1 << 20, 0L),
                (name, message) -> logs.add(message),
                Map.of());

        TickResult result = null;
        for (long t = 0; t < 100; t++) {
            result = inst.tick(t, 1_000_000);
            if (!(result instanceof TickResult.Running)) {
                break;
            }
        }

        assertInstanceOf(TickResult.Finished.class, result);
        // Parent (spawned first) and child are both woken at the same tick; the parent
        // logs "P" before the child logs "C" because scheduling is in spawn order.
        assertEquals(List.of("P", "C"), logs);
    }
}
