package com.jhuanglululu.wasm;

/**
 * Resource limits for a table or memory: a minimum size and an optional maximum.
 * {@code max == -1} means no maximum was specified. Sizes are stored as {@code long}
 * to hold the full unsigned {@code u32} range without overflow.
 */
public record Limits(long min, long max) {

    /** True if an explicit maximum was declared. */
    public boolean hasMax() {
        return max >= 0;
    }
}
