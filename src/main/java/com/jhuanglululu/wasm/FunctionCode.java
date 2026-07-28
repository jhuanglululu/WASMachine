package com.jhuanglululu.wasm;

/**
 * The compiled body of one defined (non-imported) function.
 *
 * @param typeIndex index into the module's type section for this function's signature
 * @param locals    the declared local value types, expanded (one entry per local),
 *                  <em>not</em> including the function parameters
 * @param body      the raw instruction bytes, starting at the first instruction
 *                  (after the locals declaration) and ending with the function's
 *                  final {@code end}. Executed later by the interpreter; offsets in
 *                  {@link #sideTable()} are indices into this array.
 * @param sideTable precomputed control-flow metadata (see {@link SideTable})
 */
@SuppressWarnings("ArrayRecordComponent") // identity equality is fine; arrays are shared immutably on the hot path
public record FunctionCode(int typeIndex, ValType[] locals, byte[] body, SideTable sideTable) {

    @Override
    public ValType[] locals() {
        return locals.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    /** The internal body array (no defensive copy) for the interpreter's hot path. */
    byte[] rawBody() {
        return body;
    }

    /** The internal locals array (no defensive copy) for the interpreter's hot path. */
    ValType[] rawLocals() {
        return locals;
    }
}
