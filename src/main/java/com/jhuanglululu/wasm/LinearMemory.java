package com.jhuanglululu.wasm;

/**
 * One instance's linear memory: the byte array plus its current and maximum page counts.
 *
 * <p>It is a mutable holder rather than three {@link ExecutionContext} fields because every
 * task of an instance points at the <em>same</em> one. Tasks share memory (engine ABI 2): a
 * store or a {@code memory.grow} by any task is immediately visible to all of them, which is
 * what makes a guest pointer meaningful across a {@code spawn}. Sharing is safe without
 * locking because an instance's tasks are cooperative — one runs at a time, switching only at
 * blocking points — so no two tasks ever touch these fields concurrently.
 *
 * <p>{@link #data} is replaced (not resized in place) by a grow, so nothing may cache the
 * array across an instruction that can grow memory; the interpreter reads it through this
 * holder on every access for exactly that reason.
 */
public final class LinearMemory {

    /** The bytes. Replaced by a grow — never cache it across a growing instruction. */
    byte[] data = new byte[0];

    /** Current size in pages ({@code data.length == pages * PAGE}). */
    int pages;

    /** The growth ceiling in pages, from the module's memory limits. */
    int maxPages;
}
