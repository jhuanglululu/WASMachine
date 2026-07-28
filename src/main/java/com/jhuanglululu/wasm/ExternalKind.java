package com.jhuanglululu.wasm;

/** The four kinds of importable/exportable entity. */
public enum ExternalKind {
    FUNCTION,
    TABLE,
    MEMORY,
    GLOBAL;

    static ExternalKind fromByte(int b, int offset) {
        return switch (b) {
            case 0x00 -> FUNCTION;
            case 0x01 -> TABLE;
            case 0x02 -> MEMORY;
            case 0x03 -> GLOBAL;
            default -> throw new WasmParseException(
                    "invalid external kind 0x" + Integer.toHexString(b) + " at offset " + offset);
        };
    }
}
