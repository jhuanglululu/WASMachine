package com.jhuanglululu.wasm;

import java.util.List;

/**
 * An element segment (table initializer). Reference-types encoding is supported,
 * so a segment is active, passive, or declarative, and its items are given either
 * as function indices (the {@code funcref} short form) or as constant expressions.
 *
 * <ul>
 *   <li>{@link Mode#ACTIVE}: {@link #tableIndex()} and {@link #offset()} are set.</li>
 *   <li>{@link Mode#PASSIVE}/{@link Mode#DECLARATIVE}: {@code offset} is {@code null}.</li>
 * </ul>
 *
 * Exactly one of {@link #functionIndices()} / {@link #initExpressions()} is non-null,
 * depending on the encoding form.
 */
@SuppressWarnings("ArrayRecordComponent") // identity equality is fine; indices are copied into the table once
public record ElementSegment(
        Mode mode,
        int tableIndex,
        ConstExpr offset,
        ValType elementType,
        int[] functionIndices,
        List<ConstExpr> initExpressions) {

    public enum Mode {
        ACTIVE,
        PASSIVE,
        DECLARATIVE
    }

    /** True if items are given as a list of function indices (the short {@code funcref} form). */
    public boolean isFunctionIndexForm() {
        return functionIndices != null;
    }
}
