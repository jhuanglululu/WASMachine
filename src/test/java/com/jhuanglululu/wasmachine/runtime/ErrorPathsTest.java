package com.jhuanglululu.wasmachine.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jhuanglululu.wasm.Module;
import com.jhuanglululu.wasmachine.runtime.SyncWasm.P;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Error paths and the terminal contract: {@code fail()}, a missing or out-of-range handshake
 * export, instruction-budget exhaustion, and the raw {@code i32} a finished run hands back.
 */
class ErrorPathsTest {

    private static MachineInstance instance(byte[] moduleBytes, long cap) {
        return instance(moduleBytes, cap, SyncRun.ENGINE_ABI);
    }

    /** An instance whose handshake expectation the test chooses, to pin the range check. */
    private static MachineInstance instance(byte[] moduleBytes, long cap,
            MachineInstance.AbiCheck check) {
        return new MachineInstance(Module.parse(moduleBytes),
                new MachineInstance.Config("err", RuntimeWasm.ENGINE_MODULE, "_engine_main",
                        List.of(check), cap, 0L),
                (name, message) -> { }, Map.of());
    }

    private static TickResult drive(MachineInstance inst, long budget) {
        TickResult result = null;
        for (long t = 0; t < 100; t++) {
            result = inst.tick(t, budget);
            if (!(result instanceof TickResult.Running)) {
                break;
            }
        }
        return result;
    }

    private static void assertErroredContains(TickResult r, String needle) {
        assertInstanceOf(TickResult.Errored.class, r);
        String msg = ((TickResult.Errored) r).message().toLowerCase(Locale.ROOT);
        assertTrue(msg.contains(needle.toLowerCase(Locale.ROOT)),
                "expected message to contain \"" + needle + "\" but was: "
                        + ((TickResult.Errored) r).message());
    }

    @Test
    void failRoutesToErroredWithMessage() {
        MachineInstance inst = instance(RuntimeWasm.failModule(), 1 << 20);
        TickResult r = drive(inst, 1_000_000);
        assertErroredContains(r, "boom");
    }

    @Test
    void missingAbiExportIsErrored() {
        MachineInstance inst = instance(RuntimeWasm.missingAbiModule(), 1 << 20);
        TickResult r = drive(inst, 1_000_000);
        assertErroredContains(r, "abi");
    }

    @Test
    void instructionBudgetExhaustionIsErrored() {
        MachineInstance inst = instance(RuntimeWasm.infiniteLoopModule(), 1 << 20);
        // A tiny budget cannot let the infinite loop reach any blocking point.
        TickResult r = inst.tick(0, 1000);
        assertErroredContains(r, "budget");
    }

    @Test
    void handshakeOutsideTheConfiguredRangeIsErrored() {
        // The check names the export, what it returned and what this host accepts.
        MachineInstance inst = instance(SyncWasm.module(new P(), 3), 1 << 20);
        String message = inst.loadError().orElseThrow();
        assertTrue(message.contains("_engine_abi") && message.contains("returned 3")
                && message.contains("1..1"), message);
        assertErroredContains(inst.tick(0, 1_000_000), "handshake");
    }

    @Test
    void handshakeInsideTheRangeLoadsCleanly() {
        // The engine speaks exactly one version today, but the check is a range: a host
        // accepting 2..4 takes a guest reporting 3 and refuses one reporting 1.
        assertEquals(true, instance(SyncWasm.module(new P()), 1 << 20).loadError().isEmpty());
        MachineInstance.AbiCheck wide = new MachineInstance.AbiCheck("_engine_abi", 2, 4);
        assertEquals(true,
                instance(SyncWasm.module(new P(), 3), 1 << 20, wide).loadError().isEmpty());
        assertTrue(instance(SyncWasm.module(new P(), 1), 1 << 20, wide)
                .loadError().orElseThrow().contains("2..4"));
    }

    @Test
    void theEngineHandshakeVersionIsWhatTheFixturesReport() {
        // One constant, referenced by the fixtures and by every future embedder.
        assertEquals(1, MachineInstance.ENGINE_ABI_VERSION);
    }

    @Test
    void mainsRawExitValueIsHandedBackUntouched() {
        // The engine attaches no meaning to the i32 main returns — 7 is neither valid nor
        // invalid here, it is just what the guest said. Interpretation is the embedder's.
        MachineInstance inst = instance(SyncWasm.module(new P().i32(7).raw(0x0F)), 1 << 20);
        TickResult r = drive(inst, 1_000_000);
        assertInstanceOf(TickResult.Finished.class, r);
        assertEquals(7, ((TickResult.Finished) r).exitValue());
    }

    @Test
    void exitOnTaskZeroFinishesWithZero() {
        MachineInstance inst = instance(SyncWasm.module(new P().call(SyncWasm.EXIT)), 1 << 20);
        TickResult r = drive(inst, 1_000_000);
        assertInstanceOf(TickResult.Finished.class, r);
        assertEquals(0, ((TickResult.Finished) r).exitValue());
    }
}
