package com.jhuanglululu.wasm;

/**
 * Thrown when a WebAssembly module cannot be parsed: malformed bytes (bad magic,
 * truncated sections, overlong LEB128, out-of-bounds indices) or a feature this
 * parser deliberately does not support (SIMD, atomics, exception handling, tail
 * calls, multi-memory, GC).
 *
 * <p>Unchecked so the parser can be used fluently; every message names the
 * concrete problem (and, for gated features, the feature) so load failures are
 * actionable. The parser never throws raw {@link ArrayIndexOutOfBoundsException}
 * or hangs — every allocation is bounded by the actual input size and every read
 * is bounds-checked into this exception.
 */
public final class WasmParseException extends RuntimeException {

    public WasmParseException(String message) {
        super(message);
    }

    public WasmParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
