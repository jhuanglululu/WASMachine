package com.jhuanglululu.wasm;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jhuanglululu.wasm.WasmBuilder.Buf;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The operand-stack assertions. There is deliberately no wasm operand-type validator (the only
 * input is rustc output), but every frame shares one stack array, so a hand-built module with
 * the wrong stack depth would silently read its caller's slots. These are the guards that turn
 * that into a loud failure wherever hand-built modules exist — the test JVM runs with
 * {@code -ea}, so this test also proves the guards are actually live.
 */
class OperandStackGuardTest {

    private static void run(Buf body) {
        byte[] bytes = new TestModule().body(body).build();
        Instance inst = new Instance(Module.parse(bytes), Map.of());
        ExecutionContext ctx = inst.instantiate();
        assertThrows(AssertionError.class, () -> inst.invoke(ctx, "main", new long[0], 1000));
    }

    @Test
    void droppingBelowTheFrameBaseIsAnAssertionError() {
        run(new Buf().raw(0x41, 0x01, 0x1A, 0x1A)); // i32.const 1; drop; drop
    }

    @Test
    void poppingAnOperandBelowTheFrameBaseIsAnAssertionError() {
        run(new Buf().raw(0x41, 0x01, 0x6A)); // i32.const 1; i32.add (one operand short)
    }
}
