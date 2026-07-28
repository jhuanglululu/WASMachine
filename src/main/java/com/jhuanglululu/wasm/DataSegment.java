package com.jhuanglululu.wasm;

/**
 * A data segment. Both active and passive segments are parsed. For an active
 * segment {@link #offset()} is set (and {@link #memoryIndex()} is 0 — multi-memory
 * is rejected); for a passive segment {@code offset} is {@code null}.
 */
@SuppressWarnings("ArrayRecordComponent") // identity equality is fine; segment bytes are copied into memory once
public record DataSegment(Mode mode, int memoryIndex, ConstExpr offset, byte[] data) {

    public enum Mode {
        ACTIVE,
        PASSIVE
    }

    public DataSegment {
        data = data.clone();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }

    /** The internal data array (no defensive copy) for the interpreter's hot path. */
    byte[] rawData() {
        return data;
    }
}
