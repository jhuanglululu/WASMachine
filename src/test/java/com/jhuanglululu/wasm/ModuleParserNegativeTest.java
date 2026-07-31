package com.jhuanglululu.wasm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Negative tests: malformed input and gated features. Every case must throw
 * {@link WasmParseException} (never a raw {@link ArrayIndexOutOfBoundsException},
 * never a hang), and feature rejections must name the feature.
 */
class ModuleParserNegativeTest {

    private static WasmParseException parseFails(byte[] bytes) {
        return assertThrows(WasmParseException.class, () -> Module.parse(bytes));
    }

    private static void assertMessageContains(WasmParseException e, String needle) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        assertTrue(msg.contains(needle.toLowerCase(Locale.ROOT)),
                "expected message to contain \"" + needle + "\" but was: " + e.getMessage());
    }

    private static Buf typeVoidVoid() {
        return new Buf().vec(1).raw(0x60, 0x00, 0x00);
    }

    /** A one-function module whose body is the given instruction bytes. */
    private static byte[] functionModule(Buf instructions) {
        Buf body = new Buf().vec(0).buf(instructions);
        Buf code = new Buf().vec(1).uleb(body.toBytes().length).buf(body);
        return new WasmBuilder()
                .section(1, typeVoidVoid())
                .section(3, new Buf().vec(1).uleb(0))
                .section(5, new Buf().vec(1).raw(0x00, 0x01)) // a memory (so memory ops are in-range)
                .section(10, code)
                .build();
    }

    @Test
    void badMagic() {
        WasmParseException e = parseFails(new byte[] {0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00});
        assertMessageContains(e, "magic");
    }

    @Test
    void badVersion() {
        WasmParseException e = parseFails(new byte[] {0x00, 0x61, 0x73, 0x6D, 0x02, 0x00, 0x00, 0x00});
        assertMessageContains(e, "version");
    }

    @Test
    void emptyInputIsNotAModule() {
        parseFails(new byte[0]); // must not throw AIOOB
    }

    @Test
    void truncatedSectionLengthOverrunsInput() {
        // Section id 1 declares length 20 but only 2 bytes follow.
        Buf raw = new Buf().bytes(WasmBuilder.PREAMBLE)
                .u8(1).uleb(20).raw(0x01, 0x60);
        WasmParseException e = parseFails(raw.toBytes());
        assertMessageContains(e, "exceeds remaining");
    }

    @Test
    void sectionContentOverrun() {
        // Type section claims 5 entries but the section body holds only one.
        WasmParseException e = parseFails(new WasmBuilder()
                .section(1, new Buf().vec(5).raw(0x60, 0x00, 0x00))
                .build());
        assertMessageContains(e, "exceeds remaining");
    }

    @Test
    void trailingBytesInSection() {
        // A well-formed type section followed by a stray byte inside the declared length.
        WasmParseException e = parseFails(new WasmBuilder()
                .section(1, new Buf().vec(1).raw(0x60, 0x00, 0x00, 0xFF))
                .build());
        assertMessageContains(e, "trailing");
    }

    @Test
    void overlongLeb128() {
        // A u32 (the type-vector count) encoded in 6 bytes with continuation set.
        WasmParseException e = parseFails(new WasmBuilder()
                .section(1, new Buf().raw(0x80, 0x80, 0x80, 0x80, 0x80, 0x00))
                .build());
        assertMessageContains(e, "long");
    }

    @Test
    void simdOpcodeIsRejectedByName() {
        WasmParseException e = parseFails(functionModule(new Buf().raw(0xFD, 0x00, 0x0B)));
        String msg = e.getMessage();
        assertTrue(msg.contains("SIMD") || msg.contains("0xFD"),
                "expected SIMD/0xFD in: " + msg);
    }

    @Test
    void atomicsOpcodeIsRejectedByName() {
        WasmParseException e = parseFails(functionModule(new Buf().raw(0xFE, 0x00, 0x0B)));
        String msg = e.getMessage();
        assertTrue(msg.contains("atomics") || msg.contains("0xFE"),
                "expected atomics/0xFE in: " + msg);
    }

    @Test
    void tailCallIsRejectedByName() {
        WasmParseException e = parseFails(functionModule(new Buf().raw(0x12, 0x00, 0x0B)));
        assertMessageContains(e, "tail call");
    }

    @Test
    void exceptionHandlingIsRejectedByName() {
        WasmParseException e = parseFails(functionModule(new Buf().raw(0x1F, 0x00, 0x0B)));
        assertMessageContains(e, "exception handling");
    }

    @Test
    void multiMemoryInSectionIsRejected() {
        WasmParseException e = parseFails(new WasmBuilder()
                .section(5, new Buf().vec(2).raw(0x00, 0x01).raw(0x00, 0x01))
                .build());
        assertMessageContains(e, "multi-memory");
    }

    @Test
    void multiMemoryInstructionIndexIsRejected() {
        // memory.size with a non-zero memory index byte.
        WasmParseException e = parseFails(functionModule(new Buf().raw(0x3F, 0x01, 0x0B)));
        assertMessageContains(e, "multi-memory");
    }

    @Test
    void v128ValueTypeIsRejectedAsSimd() {
        // Type section with a v128 (0x7B) result.
        WasmParseException e = parseFails(new WasmBuilder()
                .section(1, new Buf().vec(1).raw(0x60, 0x00, 0x01, 0x7B))
                .build());
        assertMessageContains(e, "simd");
    }

    @Test
    void branchLabelOutOfRange() {
        // br 5 with no enclosing blocks (only the function label at depth 0).
        WasmParseException e = parseFails(functionModule(new Buf().raw(0x0C, 0x05, 0x0B)));
        assertMessageContains(e, "branch label");
    }

    @Test
    void callTargetOutOfRange() {
        WasmParseException e = parseFails(functionModule(new Buf().raw(0x10, 0x09, 0x0B)));
        assertMessageContains(e, "call target");
    }

    @Test
    void codeCountMismatch() {
        // One declared function but no code section.
        WasmParseException e = parseFails(new WasmBuilder()
                .section(1, typeVoidVoid())
                .section(3, new Buf().vec(1).uleb(0))
                .build());
        assertMessageContains(e, "function");
    }

    @Test
    void memoryInitWithoutDataCountIsRejected() {
        // memory.init present but no DataCount section.
        WasmParseException e = parseFails(functionModule(new Buf().raw(0xFC, 0x08, 0x00, 0x00, 0x0B)));
        assertMessageContains(e, "datacount");
    }

    @Test
    void nullInputThrows() {
        assertThrows(WasmParseException.class, () -> Module.parse(null));
    }

    @Test
    void dataCountMismatchIsRejected() {
        WasmParseException e = parseFails(new WasmBuilder()
                .section(5, new Buf().vec(1).raw(0x00, 0x01))
                .section(12, new Buf().uleb(3))                 // says 3
                .section(11, new Buf().vec(1).uleb(1).uleb(0))  // but only 1
                .build());
        assertMessageContains(e, "datacount");
    }

    @Test
    void sectionOutOfOrderIsRejected() {
        // Function section (id 3) before type section (id 1).
        WasmParseException e = parseFails(new WasmBuilder()
                .section(3, new Buf().vec(0))
                .section(1, typeVoidVoid())
                .build());
        assertMessageContains(e, "out of order");
    }

    @Test
    void tooManyLocalsIsRejected() {
        // A single local declaration claiming ~4 billion locals must be rejected,
        // not allocated.
        Buf localsAndCode = new Buf().vec(1).uleb(0xFFFFFFFFL).raw(0x7F).raw(0x0B);
        Buf code = new Buf().vec(1).uleb(localsAndCode.toBytes().length).buf(localsAndCode);
        WasmParseException e = parseFails(new WasmBuilder()
                .section(1, typeVoidVoid())
                .section(3, new Buf().vec(1).uleb(0))
                .section(10, code)
                .build());
        assertMessageContains(e, "too many locals");
    }

    @Test
    void exportIndexOutOfRange() {
        WasmParseException e = parseFails(new WasmBuilder()
                .section(7, new Buf().vec(1).name("nope").raw(0x00).uleb(0))
                .build());
        assertMessageContains(e, "out of range");
    }

    @Test
    void unknownOpcodeIsRejected() {
        // 0x1D is not a defined opcode.
        WasmParseException e = parseFails(functionModule(new Buf().raw(0x1D, 0x0B)));
        assertEquals(true, e.getMessage().contains("0x1d") || e.getMessage().contains("invalid"));
    }
}
