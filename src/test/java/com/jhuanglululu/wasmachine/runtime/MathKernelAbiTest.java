package com.jhuanglululu.wasmachine.runtime;

import static com.jhuanglululu.wasmachine.runtime.SyncWasm.ATAN2;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.CBRT;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.COS;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.FORMAT_F64;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.LN;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.LOG;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.LOG10;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.POW;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.SCRATCH;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jhuanglululu.wasmachine.runtime.SyncWasm.P;
import org.junit.jupiter.api.Test;

/**
 * The math kernel over the real ABI: a guest calling the imports through the interpreter, under
 * the engine's own module name. What is under test here is the protocol rather than the maths —
 * argument order across the wasm boundary, and {@code format_f64}'s write-what-fits/return-what-
 * was-needed contract, which is what lets a guest retry with a bigger buffer without a blocking
 * point in between.
 */
class MathKernelAbiTest {

    /** One untouched byte of scratch memory, as it reads back through {@code log}. */
    private static final String NUL = String.valueOf('\0');

    /** Formats into scratch, then logs exactly the bytes the kernel said it needed. */
    private static P formatThenLog(double x, int precision, int cap) {
        return new P()
                .f64(x).i32(precision).i32(SCRATCH).i32(cap).call(FORMAT_F64)
                .set(0)                              // local 0 = bytes needed
                .i32(SCRATCH).get(0).call(LOG);
    }

    @Test
    void aGuestReachesTheKernelThroughTheEngineModule() {
        // Each result is compared in the guest, so a wrong value logs nothing at all.
        P main = new P()
                .f64(-8).call(CBRT).ifEqF64(-2.0, new P().log(0))
                .f64(2).f64(3).call(POW).ifEqF64(8.0, new P().log(1))
                .f64(1000).call(LOG10).ifEqF64(3.0, new P().log(2))
                .f64(1).call(LN).ifEqF64(0.0, new P().log(3))
                .f64(0).call(COS).ifEqF64(1.0, new P().log(4));

        assertEquals("ABCDE", SyncRun.run(main).assertFinished().trace());
    }

    @Test
    void binaryArgumentsArriveInDeclarationOrder() {
        // atan2(y, x): swapping the two would give pi/4 here instead of the quarter turn.
        P main = new P().f64(1).f64(0).call(ATAN2)
                .ifEqF64(StrictMath.atan2(1.0, 0.0), new P().log(0));

        assertEquals("A", SyncRun.run(main).assertFinished().trace());
    }

    @Test
    void formatWritesTheTextAndReturnsItsLength() {
        P main = new P()
                .f64(1.5).i32(-1).i32(SCRATCH).i32(64).call(FORMAT_F64)
                .set(0)
                .get(0).ifEq(3, new P().log(0))      // 'A' iff it reported 3 bytes needed
                .i32(SCRATCH).get(0).call(LOG);

        assertEquals("A1.5", SyncRun.run(main).assertFinished().trace());
    }

    @Test
    void theGuestSeesExactlyTheHostSideText() {
        // The same three values the pinned vector table covers, round-tripped through memory.
        assertEquals("-0.00", SyncRun.run(formatThenLog(-0.0, 2, 64)).assertFinished().trace());
        assertEquals("1.00", SyncRun.run(formatThenLog(1.005, 2, 64)).assertFinished().trace());
        assertEquals("NaN", SyncRun.run(formatThenLog(Double.NaN, 3, 64)).assertFinished().trace());
    }

    @Test
    void aLongResultNeedsOnlyABigEnoughBuffer() {
        // 1e300 in plain notation is 301 bytes — the case a fixed 32-byte guest buffer misses,
        // and exactly why the call reports what it needed instead of truncating silently.
        String expected = "1" + "0".repeat(300);
        assertEquals(expected,
                SyncRun.run(formatThenLog(1e300, -1, 512)).assertFinished().trace());
    }

    @Test
    void neededBeyondCapWritesExactlyCapBytesAndNoMore() {
        // cap 2 of the 3 bytes "1.5": the guest reads back "1." and two untouched NULs, and
        // still learns it needed 3.
        P main = new P()
                .f64(1.5).i32(-1).i32(SCRATCH).i32(2).call(FORMAT_F64)
                .ifEq(3, new P().log(0))
                .i32(SCRATCH).i32(4).call(LOG);

        assertEquals("A1." + NUL.repeat(2), SyncRun.run(main).assertFinished().trace());
    }

    @Test
    void capZeroWritesNothingButStillMeasures() {
        // The sizing call of the two-call protocol: measure first, allocate, then format.
        P main = new P()
                .f64(1.5).i32(-1).i32(SCRATCH).i32(0).call(FORMAT_F64)
                .ifEq(3, new P().log(0))
                .i32(SCRATCH).i32(3).call(LOG);

        assertEquals("A" + NUL.repeat(3), SyncRun.run(main).assertFinished().trace());
    }

    @Test
    void aPrecisionTheKernelCannotMeanKillsTheInstance() {
        P tooMany = new P().f64(1.0).i32(18).i32(SCRATCH).i32(64).call(FORMAT_F64).drop();
        SyncRun.run(tooMany).assertKilled("format_f64", "precision 18", "-1..17");

        P negative = new P().f64(1.0).i32(-2).i32(SCRATCH).i32(64).call(FORMAT_F64).drop();
        SyncRun.run(negative).assertKilled("format_f64", "precision -2");
    }

    @Test
    void aNegativeCapacityKills() {
        // A buffer cannot be -1 bytes long; like every other bad argument this is loud.
        P main = new P().f64(1.0).i32(-1).i32(SCRATCH).i32(-1).call(FORMAT_F64).drop();
        SyncRun.run(main).assertKilled("format_f64", "negative buffer capacity -1");
    }

    @Test
    void domainErrorsDoNotKillAGuest() {
        // ln(-1) is NaN and ln(0) is -inf; both flow on into guest arithmetic untouched, and
        // the run reaches its end — the kernel never decides a domain error is fatal.
        P main = new P()
                .f64(-1).call(LN).ifEqF64(0.0, new P().log(1))  // NaN equals nothing: no 'B'
                .f64(0).call(LN).ifEqF64(Double.NEGATIVE_INFINITY, new P().log(2))
                .log(0);

        assertEquals("CA", SyncRun.run(main).assertFinished().trace());
    }
}
