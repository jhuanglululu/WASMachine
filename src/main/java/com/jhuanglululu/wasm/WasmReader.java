package com.jhuanglululu.wasm;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * A bounds-checked cursor over a byte array with the LEB128 and primitive
 * decoders the WebAssembly binary format needs. Every read validates against the
 * configured limit and throws {@link WasmParseException} on truncation or malformed
 * encoding — callers never see {@link ArrayIndexOutOfBoundsException}.
 *
 * <p>A reader has a {@code limit} (never past the backing array) so a section can
 * be parsed against a sub-range without copying: {@link #subReader(int)} yields a
 * child bounded to the next {@code n} bytes and advances this reader past them.
 */
final class WasmReader {

    private final byte[] data;
    private int pos;
    private final int limit;

    WasmReader(byte[] data) {
        this(data, 0, data.length);
    }

    private WasmReader(byte[] data, int start, int limit) {
        this.data = data;
        this.pos = start;
        this.limit = limit;
    }

    int position() {
        return pos;
    }

    int remaining() {
        return limit - pos;
    }

    boolean hasRemaining() {
        return pos < limit;
    }

    private void require(int n) {
        // n is always small and non-negative here; guard against overflow anyway.
        if (n < 0 || pos + n > limit || pos + n < pos) {
            throw new WasmParseException(
                    "unexpected end of input: needed " + n + " byte(s) at offset " + pos
                            + " but only " + (limit - pos) + " remain");
        }
    }

    int readByte() {
        require(1);
        return data[pos++] & 0xFF;
    }

    int peekByte() {
        require(1);
        return data[pos] & 0xFF;
    }

    byte[] readBytes(int n) {
        require(n);
        byte[] out = new byte[n];
        System.arraycopy(data, pos, out, 0, n);
        pos += n;
        return out;
    }

    /** Advances {@code n} bytes without copying (used to skip custom sections). */
    void skip(int n) {
        require(n);
        pos += n;
    }

    /** A copy of {@code data[from, to)} in absolute positions of the backing array. */
    byte[] slice(int from, int to) {
        if (from < 0 || to < from || to > limit) {
            throw new WasmParseException("invalid slice [" + from + ", " + to + ")");
        }
        byte[] out = new byte[to - from];
        System.arraycopy(data, from, out, 0, to - from);
        return out;
    }

    /**
     * Reads a {@code u32} vector length and rejects it if it cannot fit in the
     * remaining input (each vector element occupies at least one byte). This bounds
     * every element-vector allocation by the actual input size.
     */
    int readVecCount() {
        int n = readU32();
        if (Integer.toUnsignedLong(n) > remaining()) {
            throw new WasmParseException("vector count " + Integer.toUnsignedLong(n)
                    + " exceeds remaining input (" + remaining() + " bytes) at offset " + pos);
        }
        return n;
    }

    /**
     * Returns a child reader bounded to the next {@code n} bytes and advances this
     * reader past them. Used to confine each section to its declared length so an
     * over-long body can never read into the following section.
     */
    WasmReader subReader(int n) {
        require(n);
        WasmReader child = new WasmReader(data, pos, pos + n);
        pos += n;
        return child;
    }

    // --- LEB128 ---

    /**
     * Unsigned LEB128 with an explicit bit ceiling. Rejects overlong encodings and
     * values that do not fit in {@code bits} (the classic "integer too large" and
     * "integer representation too long" malformations).
     */
    private long readUnsignedLeb(int bits) {
        long result = 0;
        int shift = 0;
        while (true) {
            int b = readByte();
            if (shift + 7 < bits) {
                result |= ((long) (b & 0x7F)) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
                shift += 7;
            } else {
                // Final byte: only (bits - shift) low bits may be set, and the
                // continuation bit must be clear.
                int remainingBits = bits - shift;
                int mask = (0xFF << remainingBits) & 0x7F;
                if ((b & 0x80) != 0) {
                    throw new WasmParseException("integer representation too long at offset " + (pos - 1));
                }
                if ((b & mask) != 0) {
                    throw new WasmParseException("integer too large at offset " + (pos - 1));
                }
                result |= ((long) (b & 0x7F)) << shift;
                return result;
            }
        }
    }

    /**
     * Signed LEB128 with an explicit bit ceiling. Rejects overlong encodings and
     * values that do not fit in {@code bits}, and sign-extends the result.
     */
    private long readSignedLeb(int bits) {
        long result = 0;
        int shift = 0;
        int b;
        while (true) {
            b = readByte();
            if (shift + 7 < bits) {
                result |= ((long) (b & 0x7F)) << shift;
                shift += 7;
                if ((b & 0x80) == 0) {
                    break;
                }
            } else {
                int remainingBits = bits - shift;
                // The value bits of the last byte, plus the sign bit, must be a
                // consistent sign-extension; otherwise the encoding is malformed.
                int signBit = 1 << (remainingBits - 1);
                int usableMask = (signBit << 1) - 1; // low remainingBits bits
                int high = b & ~usableMask & 0x7F;
                boolean neg = (b & signBit) != 0;
                if ((b & 0x80) != 0) {
                    throw new WasmParseException("integer representation too long at offset " + (pos - 1));
                }
                // For a well-formed value the high (unused) bits must be all-zero
                // (non-negative) or all-one (negative, matching the sign bit).
                int expectedHigh = neg ? (~usableMask & 0x7F) : 0;
                if (high != expectedHigh) {
                    throw new WasmParseException("integer too large at offset " + (pos - 1));
                }
                result |= ((long) (b & 0x7F)) << shift;
                shift += 7;
                break;
            }
        }
        if (shift < 64 && (b & 0x40) != 0) {
            result |= -(1L << shift);
        }
        return result;
    }

    /** Unsigned 32-bit LEB128 (e.g. indices, counts, lengths). */
    int readU32() {
        return (int) readUnsignedLeb(32);
    }

    /** Signed 32-bit LEB128 ({@code i32.const}). */
    int readS32() {
        return (int) readSignedLeb(32);
    }

    /** Signed 33-bit LEB128 (block-type: negative encodes empty/valtype, non-negative a type index). */
    long readS33() {
        return readSignedLeb(33);
    }

    /** Signed 64-bit LEB128 ({@code i64.const}). */
    long readS64() {
        return readSignedLeb(64);
    }

    int readF32Bits() {
        require(4);
        int v = (data[pos] & 0xFF)
                | ((data[pos + 1] & 0xFF) << 8)
                | ((data[pos + 2] & 0xFF) << 16)
                | ((data[pos + 3] & 0xFF) << 24);
        pos += 4;
        return v;
    }

    long readF64Bits() {
        require(8);
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= ((long) (data[pos + i] & 0xFF)) << (8 * i);
        }
        pos += 8;
        return v;
    }

    /** A {@code u32}-prefixed UTF-8 name, strictly validated. */
    String readName() {
        int len = readU32();
        byte[] bytes = readBytes(len);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new WasmParseException("invalid UTF-8 in name at offset " + (pos - len), e);
        }
    }
}
