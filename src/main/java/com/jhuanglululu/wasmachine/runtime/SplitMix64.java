package com.jhuanglululu.wasmachine.runtime;

/**
 * SplitMix64, the PRNG behind every host-side random stream of an animation instance.
 *
 * <p>The algorithm is fixed by the ABI, not an implementation detail: the guest SDK ships
 * the same generator ({@code SplitRng}) and animations may be written against exact
 * sequences, so the host must reproduce them bit for bit on every JVM. It is the reference
 * SplitMix64 (Steele/Lea/Flood 2014, as used by Java's {@code SplittableRandom} and Rust's
 * {@code SplitMix64}): the state advances by the golden-gamma constant and the output is a
 * fixed xor-shift/multiply finalizer of the new state.
 *
 * <pre>{@code
 * state += 0x9E3779B97F4A7C15
 * z = state
 * z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9
 * z = (z ^ (z >>> 27)) * 0x94D049BB133111EB
 * return z ^ (z >>> 31)
 * }</pre>
 *
 * <p>Every seed is valid (including 0) and the period is 2^64 regardless of seed.
 * Not thread-safe; one animation instance runs one task at a time.
 */
public final class SplitMix64 {

    private static final long GAMMA = 0x9E3779B97F4A7C15L;

    private long state;

    public SplitMix64(long seed) {
        this.state = seed;
    }

    /** Restarts the stream from {@code seed} (the {@code seed_random} import). */
    public void reseed(long seed) {
        this.state = seed;
    }

    /** The next 64 raw bits of the stream. */
    public long nextLong() {
        long z = state += GAMMA;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * A uniform value in {@code [0, bound)} by unsigned remainder of {@link #nextLong()}.
     *
     * <p>The modulo bias is accepted deliberately: this backs {@code notify_one(Random)}
     * over waiter-list sizes (tiny bounds, cosmetic choice), and rejection sampling would
     * make the number of draws depend on the values drawn — harder to reason about when
     * reproducing a scheduling decision by hand.
     */
    public int nextBounded(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive but was " + bound);
        }
        return (int) Long.remainderUnsigned(nextLong(), bound);
    }
}
