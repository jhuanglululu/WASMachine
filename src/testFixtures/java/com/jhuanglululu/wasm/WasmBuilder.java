package com.jhuanglululu.wasm;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

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

    /** Appends a section with an explicit (possibly wrong) declared length, for negative tests. */
    WasmBuilder sectionRawLength(int id, long declaredLength, Buf body) {
        sections.u8(id).uleb(declaredLength).bytes(body.toBytes());
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

    /** A growable byte buffer with the primitive encoders the WASM format needs. */
    static final class Buf {
        private final ByteArrayOutputStream o = new ByteArrayOutputStream();

        Buf u8(int b) {
            o.write(b & 0xFF);
            return this;
        }

        /** Writes raw bytes given as ints (convenient for opcode literals). */
        Buf raw(int... bytes) {
            for (int b : bytes) {
                o.write(b & 0xFF);
            }
            return this;
        }

        Buf bytes(byte[] a) {
            o.writeBytes(a);
            return this;
        }

        Buf buf(Buf other) {
            return bytes(other.toBytes());
        }

        Buf uleb(long value) {
            long v = value;
            do {
                int b = (int) (v & 0x7F);
                v >>>= 7;
                if (v != 0) {
                    b |= 0x80;
                }
                o.write(b);
            } while (v != 0);
            return this;
        }

        Buf sleb(long value) {
            long v = value;
            boolean more = true;
            while (more) {
                int b = (int) (v & 0x7F);
                v >>= 7;
                if ((v == 0 && (b & 0x40) == 0) || (v == -1 && (b & 0x40) != 0)) {
                    more = false;
                } else {
                    b |= 0x80;
                }
                o.write(b);
            }
            return this;
        }

        Buf f32(float f) {
            int bits = Float.floatToRawIntBits(f);
            for (int i = 0; i < 4; i++) {
                o.write((bits >>> (8 * i)) & 0xFF);
            }
            return this;
        }

        Buf f64(double d) {
            long bits = Double.doubleToRawLongBits(d);
            for (int i = 0; i < 8; i++) {
                o.write((int) ((bits >>> (8 * i)) & 0xFF));
            }
            return this;
        }

        /** A LEB-length-prefixed UTF-8 name. */
        Buf name(String s) {
            byte[] u = s.getBytes(StandardCharsets.UTF_8);
            uleb(u.length);
            return bytes(u);
        }

        /** Writes an unsigned LEB vector length (the count only). */
        Buf vec(int count) {
            return uleb(count);
        }

        byte[] toBytes() {
            return o.toByteArray();
        }
    }
}
