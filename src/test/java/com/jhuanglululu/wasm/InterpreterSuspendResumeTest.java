package com.jhuanglululu.wasm;

import static com.jhuanglululu.wasm.TestModule.I32;
import static com.jhuanglululu.wasm.TestModule.i32Const;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import com.jhuanglululu.wasm.WasmBuilder.Buf;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Suspend/resume, fuel exhaustion, and context cloning. */
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
    void cloneDivergesIndependently() {
        Instance inst = new Instance(Module.parse(suspendingModule()), Map.of("env.host", SUSPENDING));
        ExecutionContext ctx = inst.instantiate();

        assertInstanceOf(ExecResult.Suspended.class, inst.invoke(ctx, "main", new long[0], 1_000_000));

        // Clone at the suspension point; the clone is a fully independent context.
        ExecutionContext clone = ctx.copy();
        assertNotSame(ctx, clone);

        ExecResult a = inst.resume(ctx, 1_000_000, 42);
        ExecResult b = inst.resume(clone, 1_000_000, 7);

        assertEquals(42, (int) ((ExecResult.Completed) a).values()[0]);
        assertEquals(7, (int) ((ExecResult.Completed) b).values()[0]);

        // Memories diverged and do not alias.
        assertEquals(42, ctx.loadI32(0));
        assertEquals(7, clone.loadI32(0));
        assertNotEquals(ctx.loadI32(0), clone.loadI32(0));
    }
}
