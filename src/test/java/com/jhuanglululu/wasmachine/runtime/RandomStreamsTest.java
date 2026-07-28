package com.jhuanglululu.wasmachine.runtime;

import static com.jhuanglululu.wasmachine.runtime.SyncWasm.RANDOM_DET;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.RANDOM_NONDET;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.SEED_RANDOM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jhuanglululu.wasmachine.runtime.SyncWasm.P;
import org.junit.jupiter.api.Test;

/**
 * The two host random streams. Guest draws are turned into letters ({@code value & 15} indexes the
 * module's letter table) so a sequence shows up in the log trace and can be checked against
 * SplitMix64 outputs computed independently of this implementation.
 */
class RandomStreamsTest {

    /** {@code n} draws from {@code source}, each logged as the letter its low 4 bits select. */
    private static P draws(int source, int n) {
        P p = new P();
        for (int i = 0; i < n; i++) {
            p.call(source)
                    .i64(15).raw(0x83)  // i64.and
                    .raw(0xA7)          // i32.wrap_i64
                    .i32(1).call(SyncWasm.LOG);
        }
        return p;
    }

    @Test
    void deterministicStreamMatchesSplitMix64OfTheInstanceSeed() {
        // SplitMix64(0) = E220A8397B1DCDAF, 6E789E6AA1B965F4, 06C45D188009454F, F88BB8A8724C81EC;
        // low nibbles F, 4, F, C -> letters P, E, P, M.
        assertEquals("PEPM", SyncRun.run(draws(RANDOM_DET, 4)).assertFinished().trace());
    }

    @Test
    void deterministicStreamIsReproducibleAcrossInstances() {
        String first = SyncRun.seeded(draws(RANDOM_DET, 12), 0x0123456789ABCDEFL)
                .assertFinished().trace();
        String second = SyncRun.seeded(draws(RANDOM_DET, 12), 0x0123456789ABCDEFL)
                .assertFinished().trace();
        assertEquals(first, second);
        // A different instance seed must give a different sequence.
        String other = SyncRun.seeded(draws(RANDOM_DET, 12), 0x0123456789ABCDEEL)
                .assertFinished().trace();
        assertNotEquals(first, other);
    }

    @Test
    void seedRandomRestartsTheDeterministicStream() {
        // SplitMix64(12345) = 22118258A9D111A0, 346EDCE5F713F8ED, 1E9A57BC80E6721D,
        // 2D160E7E5C3F42CA; low nibbles 0, D, D, A -> letters A, N, N, K.
        P main = new P().i64(12345).call(SEED_RANDOM).append(draws(RANDOM_DET, 4));
        assertEquals("ANNK", SyncRun.run(main).assertFinished().trace());
    }

    @Test
    void seedRandomAfterDrawsRestartsFromTheSamePoint() {
        P main = new P()
                .append(draws(RANDOM_DET, 4))
                .i64(12345).call(SEED_RANDOM)
                .append(draws(RANDOM_DET, 4));
        assertEquals("PEPM" + "ANNK", SyncRun.run(main).assertFinished().trace());
    }

    @Test
    void nonDeterministicStreamVariesWithinOneRun() {
        // 16 draws from a non-deterministic source: all-equal has probability 16^-15, so this is a
        // real check that the stream is not a constant rather than a coin flip.
        String trace = SyncRun.run(draws(RANDOM_NONDET, 16)).assertFinished().trace();
        assertEquals(16, trace.length());
        assertTrue(trace.chars().distinct().count() > 1, "expected varying draws but got " + trace);
    }
}
