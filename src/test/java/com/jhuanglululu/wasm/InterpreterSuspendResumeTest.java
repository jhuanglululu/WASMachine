package com.jhuanglululu.wasm;

import static com.jhuanglululu.wasm.TestModule.I32;
import static com.jhuanglululu.wasm.TestModule.i32Const;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Suspend/resume, fuel exhaustion, and sibling (spawned-task) contexts. */
class InterpreterSuspendResumeTest {

    /**
     * A module whose {@code main()} calls the imported {@code env.host}, stores the
     * returned value at memory[0], and returns it. When the host suspends, the store
     * and return happen on resume with the supplied value.
     */
    private static byte[] suspendingModule() {
        return new TestModule()
                .types(new Buf().vec(1).raw(0x60, 0x00, 0x01, I32)) // ()->(i32)
                .importFunc("env", "host", 0)
                .mainType(0)
                .memory(1)
                .locals(new Buf().vec(1).uleb(1).raw(I32))          // 1 i32 local
                .body(new Buf()
                        .raw(0x10, 0x00)         // call host (func 0)
                        .raw(0x21, 0x00)         // local.set 0
                        .buf(i32Const(0))        // addr 0
                        .raw(0x20, 0x00)         // local.get 0
                        .raw(0x36, 0x02, 0x00)   // i32.store
                        .raw(0x20, 0x00))        // local.get 0 (return value)
                .build();
    }

    private static final HostFunction SUSPENDING = (ctx, args) -> {
        throw ctx.suspend("REQ");
    };

    @Test
    void suspendThenResumeSuppliesHostResult() {
        Instance inst = new Instance(Module.parse(suspendingModule()), Map.of("env.host", SUSPENDING));
        ExecutionContext ctx = inst.instantiate();

        ExecResult first = inst.invoke(ctx, "main", new long[0], 1_000_000);
        assertInstanceOf(ExecResult.Suspended.class, first);
        assertEquals("REQ", ((ExecResult.Suspended) first).request());

        ExecResult second = inst.resume(ctx, 1_000_000, 42);
        assertInstanceOf(ExecResult.Completed.class, second);
        assertEquals(42, (int) ((ExecResult.Completed) second).values()[0]);
        assertEquals(42, ctx.loadI32(0)); // stored to memory after resume
    }

    @Test
    void fuelExhaustionThenResumeCompletes() {
        // A countdown loop (10 iterations) returning 123; too much work for 5 fuel.
        byte[] bytes = new TestModule()
                .types(new Buf().vec(1).raw(0x60, 0x00, 0x01, I32))
                .locals(new Buf().vec(1).uleb(1).raw(I32))
                .body(new Buf()
                        .buf(i32Const(10)).raw(0x21, 0x00)   // i = 10
                        .raw(0x03, 0x40)                     // loop
                        .raw(0x20, 0x00).buf(i32Const(1)).raw(0x6B).raw(0x21, 0x00) // i -= 1
                        .raw(0x20, 0x00).raw(0x0D, 0x00)     // br_if 0
                        .raw(0x0B)                           // end loop
                        .buf(i32Const(123)))
                .build();
        Instance inst = new Instance(Module.parse(bytes), Map.of());
        ExecutionContext ctx = inst.instantiate();

        ExecResult r = inst.invoke(ctx, "main", new long[0], 5);
        assertInstanceOf(ExecResult.FuelExhausted.class, r);

        ExecResult done = inst.resume(ctx, 1_000_000);
        assertInstanceOf(ExecResult.Completed.class, done);
        assertEquals(123, (int) ((ExecResult.Completed) done).values()[0]);
    }

    @Test
    void aSiblingContextSharesMemoryAndStartsWithEmptyStacks() {
        Instance inst = new Instance(Module.parse(suspendingModule()), Map.of("env.host", SUSPENDING));
        ExecutionContext ctx = inst.instantiate();

        // Park the first context mid-host-call, then take a sibling. A sibling is a new task,
        // not a copy: it inherits no frames and no suspension.
        assertInstanceOf(ExecResult.Suspended.class, inst.invoke(ctx, "main", new long[0], 1_000_000));
        ExecutionContext sibling = ctx.spawnSibling();
        assertNotSame(ctx, sibling);
        assertEquals(0, sibling.frameCount());

        // Finish the first context: main stores its host result at address 0.
        assertEquals(42, (int) ((ExecResult.Completed) inst.resume(ctx, 1_000_000, 42)).values()[0]);
        assertEquals(42, ctx.loadI32(0));

        // Run the same function on the sibling with a different host result. Address 0 is in
        // the ONE shared memory, so the sibling's store is what both contexts now read.
        inst.prepareCall(sibling, inst.importedFunctionCount(), new long[0]);
        assertInstanceOf(ExecResult.Suspended.class, inst.resume(sibling, 1_000_000));
        assertEquals(7, (int) ((ExecResult.Completed) inst.resume(sibling, 1_000_000, 7)).values()[0]);

        assertEquals(7, sibling.loadI32(0));
        assertEquals(7, ctx.loadI32(0), "the sibling's store is visible through the first context");
    }
}
