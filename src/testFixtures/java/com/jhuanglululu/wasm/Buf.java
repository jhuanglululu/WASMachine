package com.jhuanglululu.wasm;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A growable byte buffer with the primitive encoders the WASM format needs, shared by the
 * test fixtures. LEB128 and section framing are hand-rolled here so test expectations can
 * be computed independently of the parser under test — nothing here reuses
 * {@link WasmReader} or the parser's logic.
 */
public final class Buf {
    private final ByteArrayOutputStream o = new ByteArrayOutputStream();

    public Buf u8(int b) {
        o.write(b & 0xFF);
        return this;
    }

    /** Writes raw bytes given as ints (convenient for opcode literals). */
    public Buf raw(int... bytes) {
        for (int b : bytes) {
            o.write(b & 0xFF);
        }
        return this;
    }

    public Buf bytes(byte[] a) {
        o.writeBytes(a);
        return this;
    }

    public Buf buf(Buf other) {
        return bytes(other.toBytes());
    }

    public Buf uleb(long value) {
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

    public Buf sleb(long value) {
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

    public Buf f32(float f) {
        int bits = Float.floatToRawIntBits(f);
        for (int i = 0; i < 4; i++) {
            o.write((bits >>> (8 * i)) & 0xFF);
        }
        return this;
    }

    public Buf f64(double d) {
        long bits = Double.doubleToRawLongBits(d);
        for (int i = 0; i < 8; i++) {
            o.write((int) ((bits >>> (8 * i)) & 0xFF));
        }
        return this;
    }

    /** A LEB-length-prefixed UTF-8 name. */
    public Buf name(String s) {
        byte[] u = s.getBytes(StandardCharsets.UTF_8);
        uleb(u.length);
        return bytes(u);
    }

    /** Writes an unsigned LEB vector length (the count only). */
    public Buf vec(int count) {
        return uleb(count);
    }

    /** Bytes written so far — the offset the next write lands at (for later patching). */
    public int size() {
        return o.size();
    }

    public byte[] toBytes() {
        return o.toByteArray();
    }
}
