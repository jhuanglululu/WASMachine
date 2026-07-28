package com.jhuanglululu.wasmachine.runtime;

/**
 * Computes the interpreter worker-pool size from demand, with the design's debounce:
 * grow immediately, but shrink only after demand has stayed lower for
 * {@code shrinkDelayTicks}. The target is
 * {@code max(1, min(activeInstances / 5, cores, maxThreads))}.
 *
 * <p>Pure and clock-injected (the caller passes the current tick), so it is fully
 * unit-testable without a server.
 */
public final class WorkerPoolSizer {

    private final int maxThreads;
    private final int cores;
    private final long shrinkDelayTicks;

    private int current;
    private long shrinkArmedTick = -1; // when demand first dropped below current; -1 = not armed

    public WorkerPoolSizer(int maxThreads, int cores, long shrinkDelayTicks) {
        this.maxThreads = Math.max(1, maxThreads);
        this.cores = Math.max(1, cores);
        this.shrinkDelayTicks = shrinkDelayTicks;
        this.current = 1;
    }

    /** The steady-state pool size demand implies, ignoring debounce. */
    public int target(int activeInstances) {
        int byDemand = activeInstances / 5;
        return Math.max(1, Math.min(Math.min(byDemand, cores), maxThreads));
    }

    /** The pool size currently in effect. */
    public int current() {
        return current;
    }

    /**
     * Recomputes the effective pool size at {@code currentTick}: grows to the target
     * immediately, shrinks to it only once the target has been below {@code current} for
     * at least {@code shrinkDelayTicks}.
     *
     * @return the pool size to apply now
     */
    public int update(int activeInstances, long currentTick) {
        int target = target(activeInstances);
        if (target > current) {
            current = target;
            shrinkArmedTick = -1;
        } else if (target < current) {
            if (shrinkArmedTick < 0) {
                shrinkArmedTick = currentTick;
            }
            if (currentTick - shrinkArmedTick >= shrinkDelayTicks) {
                current = target;
                shrinkArmedTick = -1;
            }
        } else {
            shrinkArmedTick = -1; // demand matches the pool again; disarm any pending shrink
        }
        return current;
    }
}
