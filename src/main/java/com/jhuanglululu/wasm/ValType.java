package com.jhuanglululu.wasm;

/**
 * A WebAssembly value type. Covers the four numeric types plus the two reference
 * types whose <em>encodings</em> this parser accepts ({@code funcref}/{@code externref});
 * {@code v128} (SIMD) and GC types are rejected during parsing.
 */
public enum ValType {
    I32(0x7F),
    I64(0x7E),
    F32(0x7D),
    F64(0x7C),
    FUNCREF(0x70),
    EXTERNREF(0x6F);

    private final int opcode;

    ValType(int opcode) {
        this.opcode = opcode;
    }

    /** The single-byte binary encoding of this value type. */
    public int opcode() {
        return opcode;
    }

    /** True for {@code funcref}/{@code externref}. */
    public boolean isReference() {
        return this == FUNCREF || this == EXTERNREF;
    }

    /**
     * Decodes a value-type byte, naming the feature for gated encodings.
     *
     * @throws WasmParseException on {@code v128} (SIMD) or any unknown/GC type
     */
    static ValType fromByte(int b, int offset) {
        return switch (b) {
            case 0x7F -> I32;
            case 0x7E -> I64;
            case 0x7D -> F32;
            case 0x7C -> F64;
            case 0x70 -> FUNCREF;
            case 0x6F -> EXTERNREF;
            case 0x7B -> throw new WasmParseException(
                    "unsupported feature SIMD (v128 value type, 0x7B) at offset " + offset);
            case 0x6E, 0x6D, 0x6C, 0x6B, 0x6A, 0x69, 0x68, 0x67 -> throw new WasmParseException(
                    "unsupported feature GC (reference/heap type 0x" + Integer.toHexString(b)
                            + ") at offset " + offset);
            default -> throw new WasmParseException(
                    "invalid value type 0x" + Integer.toHexString(b) + " at offset " + offset);
        };
    }
}
