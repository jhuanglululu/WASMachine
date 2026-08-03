package com.jhuanglululu.wasmachine.runtime;

import static com.jhuanglululu.wasmachine.runtime.SyncWasm.JOIN;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.KILL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jhuanglululu.wasm.Module;
import com.jhuanglululu.wasmachine.runtime.SyncWasm.P;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What {@code spawn} costs and what ending a task gives back, plus the ways a bad spawn entry
 * is refused.
 *
 * <p>The byte figures are hand-computed from the fixture, not read off the implementation: the
 * fixture's {@code __heap_base} is 1024 and 16-byte aligned already, the configured task stack
 * is {@link MachineInstance.Config#DEFAULT_TASK_STACK_BYTES}, and nothing else allocates — so a
 * live child means exactly one stack's worth of charge and no more.
 */
class SpawnLifecycleTest {

    private static final long BUDGET = 10_000_000L;
    private static final long CAP = 1 << 20;
    private static final int STACK = MachineInstance.Config.DEFAULT_TASK_STACK_BYTES;

    private static MachineInstance instance(P main) {
        return instance(SyncWasm.module(main));
    }

    private static MachineInstance instance(byte[] moduleBytes) {
        return new MachineInstance(Module.parse(moduleBytes),
                new MachineInstance.Config("spawn", RuntimeWasm.ENGINE_MODULE, "_engine_main",
                        List.of(SyncRun.ENGINE_ABI), CAP, 0L),
                (name, message) -> { }, Map.of());
    }

    @Test
    void aLiveChildCostsExactlyOneStackAndReturnsItWhenItEnds() {
        // Tick 0: main spawns and sleeps 1; the child takes its turn and sleeps 2 (waking at
        // tick 2). Tick 1: main joins the still-sleeping child. Tick 2: the child returns —
        // freeing its stack — which releases main, which returns and ends the instance.
        P main = new P()
                .childWithId(0, 0, new P().sleep(2))
                .sleep(1)
                .get(0).call(JOIN);
        MachineInstance inst = instance(main);

        assertInstanceOf(TickResult.Running.class, inst.tick(0, BUDGET));
        MachineInstance.StatsSnapshot live = inst.stats();
        assertEquals(2, live.liveTasks());
        assertEquals(1, live.totalSpawns());
        assertEquals(STACK, live.memoryUsedBytes(), "one live child = one stack region charged");

        assertInstanceOf(TickResult.Running.class, inst.tick(1, BUDGET));
        assertEquals(STACK, inst.stats().memoryUsedBytes(), "still alive, still charged");

        assertInstanceOf(TickResult.Finished.class, inst.tick(2, BUDGET));
        assertEquals(0, inst.stats().memoryUsedBytes(), "the ended task gave its stack back");
    }

    @Test
    void killingATaskFreesItsStackToo() {
        // The child sleeps far past the end of the run, so only the kill can end it.
        P main = new P()
                .childWithId(0, 0, new P().sleep(100))
                .sleep(1)
                .get(0).call(KILL);
        MachineInstance inst = instance(main);

        assertInstanceOf(TickResult.Running.class, inst.tick(0, BUDGET));
        assertEquals(STACK, inst.stats().memoryUsedBytes());

        assertInstanceOf(TickResult.Finished.class, inst.tick(1, BUDGET));
        assertEquals(0, inst.stats().memoryUsedBytes(), "kill frees the stack like a clean end");
        assertEquals(0, inst.stats().liveTasks());
    }

    @Test
    void twoLiveChildrenCostTwoStacksAndTheirStacksDoNotOverlap() {
        P main = new P()
                .child(0, new P().sleep(5))
                .child(0, new P().sleep(5))
                .sleep(1);
        MachineInstance inst = instance(main);

        assertInstanceOf(TickResult.Running.class, inst.tick(0, BUDGET));
        assertEquals(2L * STACK, inst.stats().memoryUsedBytes());
        assertEquals(3, inst.stats().liveTasks());
        assertEquals(2, inst.stats().totalSpawns());
    }

    @Test
    void aStackThatWouldNotFitTheCapKillsTheInstance() {
        // A 4 KiB cap cannot hold a 64 KiB stack, and the refusal must name the cap.
        MachineInstance inst = new MachineInstance(
                Module.parse(SyncWasm.module(new P().child(0, new P().sleep(1)))),
                new MachineInstance.Config("spawn", RuntimeWasm.ENGINE_MODULE, "_engine_main",
                        List.of(SyncRun.ENGINE_ABI), 4096, 0L),
                (name, message) -> { }, Map.of());

        String message = assertInstanceOf(TickResult.Errored.class, inst.tick(0, BUDGET)).message();
        assertTrue(message.contains("memory cap of 4096"), message);
    }

    @Test
    void aConfiguredStackSizeIsWhatGetsCharged() {
        // The knob is honoured, not just accepted: 8 KiB stacks charge 8 KiB per live child.
        int small = 8192;
        MachineInstance inst = new MachineInstance(
                Module.parse(SyncWasm.module(new P().child(0, new P().sleep(5)).sleep(1))),
                new MachineInstance.Config("spawn", RuntimeWasm.ENGINE_MODULE, "_engine_main",
                        List.of(SyncRun.ENGINE_ABI), CAP, 0L, Map.of(), small),
                (name, message) -> { }, Map.of());

        assertInstanceOf(TickResult.Running.class, inst.tick(0, BUDGET));
        assertEquals(small, inst.stats().memoryUsedBytes());
    }

    // --- refused spawn entries ---

    @Test
    void anEntryIndexPastTheTableKills() {
        // No tasks registered, so the fixture's table has one (null) slot; 50 is nowhere near it.
        SyncRun.run(new P().spawnRaw(50, 0).drop())
                .assertKilled("spawn", "entry index 50", "outside the function table");
    }

    @Test
    void aNegativeEntryIndexKills() {
        SyncRun.run(new P().spawnRaw(-1, 0).drop())
                .assertKilled("spawn", "entry index -1", "outside the function table");
    }

    @Test
    void aNullTableEntryKills() {
        // One registered task fills slot 0; slot 1 is the spare the fixture leaves null.
        P main = new P().child(0, new P().sleep(1)).spawnRaw(1, 0).drop();
        SyncRun.run(main).assertKilled("spawn", "function table entry 1", "null");
    }

    @Test
    void anEntryOfTheWrongSignatureKills() {
        // Function 2 is `_engine_abi`, a () -> i32 — not the fn(i32) a task entry must be.
        MachineInstance inst = instance(RuntimeWasm.spawnEntryModule(2));
        TickResult r = inst.tick(0, BUDGET);
        String message = assertInstanceOf(TickResult.Errored.class, r).message();
        assertTrue(message.contains("must be fn(i32)"), message);
    }

    @Test
    void anEntryPointingAtAHostImportKills() {
        // Function 0 is the `spawn` import itself: a table entry may name it, but the engine
        // must not enter a host function as a task.
        MachineInstance inst = instance(RuntimeWasm.spawnEntryModule(0));
        TickResult r = inst.tick(0, BUDGET);
        String message = assertInstanceOf(TickResult.Errored.class, r).message();
        assertTrue(message.contains("host import"), message);
    }

    // --- the __stack_pointer requirement ---

    @Test
    void aModuleWithoutAnExportedStackPointerCannotBeConstructed() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> instance(RuntimeWasm.missingStackPointerModule()));
        assertTrue(e.getMessage().contains("__stack_pointer"), e.getMessage());
        assertTrue(e.getMessage().contains("-C link-arg=--export=__stack_pointer"),
                "the error must name the flag that fixes it, but was: " + e.getMessage());
    }

    @Test
    void anImmutableStackPointerExportIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> instance(RuntimeWasm.immutableStackPointerModule()));
        assertTrue(e.getMessage().contains("mutable i32"), e.getMessage());
    }
}
