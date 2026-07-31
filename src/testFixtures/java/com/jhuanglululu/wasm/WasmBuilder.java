package com.jhuanglululu.wasm;

/**
 * A hand-rolled emitter of WebAssembly module bytes for tests. It writes LEB128 and
 * section framing by hand so test expectations can be computed independently of the
 * parser under test — nothing here reuses {@link WasmReader} or the parser's logic.
 */
final class WasmBuilder {

    private final Buf sections = new Buf();

    /** Standard 8-byte preamble: {@code \0asm} magic + version 1. */
    static final byte[] PREAMBLE = {0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00};

    /** Appends a section: id byte, LEB length, then the body bytes. */
    WasmBuilder section(int id, Buf body) {
        byte[] b = body.toBytes();
        sections.u8(id).uleb(b.length).bytes(b);
        return this;
    }

    byte[] build() {
        Buf m = new Buf();
        m.bytes(PREAMBLE);
        m.bytes(sections.toBytes());
        return m.toBytes();
    }

    /** Unsigned LEB128 encoding of {@code value}. */
    static byte[] uleb(long value) {
        return new Buf().uleb(value).toBytes();
    }

    /** Signed LEB128 encoding of {@code value}. */
    static byte[] sleb(long value) {
        return new Buf().sleb(value).toBytes();
    }
}
