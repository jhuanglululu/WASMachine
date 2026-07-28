package com.jhuanglululu.wasm;

/**
 * The classes of runtime trap the interpreter can raise. A trap ends the current
 * execution with {@link ExecResult.Trapped}; the embedder decides what it means.
 */
public enum TrapReason {
    UNREACHABLE("unreachable executed"),
    INTEGER_DIVIDE_BY_ZERO("integer divide by zero"),
    INTEGER_OVERFLOW("integer overflow"),
    INVALID_CONVERSION_TO_INTEGER("invalid conversion to integer"),
    OUT_OF_BOUNDS_MEMORY_ACCESS("out of bounds memory access"),
    OUT_OF_BOUNDS_TABLE_ACCESS("out of bounds table access"),
    UNINITIALIZED_ELEMENT("uninitialized element"),
    INDIRECT_CALL_TYPE_MISMATCH("indirect call type mismatch"),
    UNDEFINED_ELEMENT("undefined element"),
    CALL_STACK_EXHAUSTED("call stack exhausted");

    private final String description;

    TrapReason(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
