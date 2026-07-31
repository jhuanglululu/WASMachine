package com.jhuanglululu.wasm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Known-answer positive tests. Every module is built by hand with {@link WasmBuilder};
 * all expected values are computed independently of the parser.
 */
class ModuleParserPositiveTest {

    /** Type section body for a single {@code () -> ()} signature. */
    private static Buf typeVoidVoid() {
        return new Buf().vec(1).raw(0x60, 0x00, 0x00);
    }

    /** Wraps instruction bytes as a one-function code section (locals count 0). */
    private static Buf codeSection(Buf instructions) {
        Buf body = new Buf().vec(0).buf(instructions);
        return new Buf().vec(1).uleb(body.toBytes().length).buf(body);
    }

    @Test
    void minimalModule() {
        Module m = Module.parse(new WasmBuilder().build());
        assertEquals(0, m.types().size());
        assertEquals(0, m.functionCount());
        assertEquals(0, m.imports().size());
        assertEquals(0, m.exports().size());
        assertTrue(m.startFunction().isEmpty());
        assertEquals(-1, m.dataCount());
    }

    @Test
    void typedFunctionWithBody() {
        WasmBuilder b = new WasmBuilder()
                .section(1, typeVoidVoid())
                .section(3, new Buf().vec(1).uleb(0))
                .section(10, codeSection(new Buf().raw(0x0B))); // just `end`

        Module m = Module.parse(b.build());
        assertEquals(1, m.types().size());
        assertEquals(1, m.functionCount());
        assertEquals(0, m.importedFunctionCount());
        assertEquals(1, m.code().size());

        FunctionCode fc = m.code().get(0);
        assertEquals(0, fc.typeIndex());
        assertEquals(0, fc.locals().length);
        assertArrayEquals(new byte[] {0x0B}, fc.body());
    }

    @Test
    void functionWithLocals() {
        // locals: 2 i32, 1 i64
        Buf localsAndCode = new Buf()
                .vec(2)                 // 2 local declarations
                .uleb(2).raw(0x7F)      // 2 x i32
                .uleb(1).raw(0x7E)      // 1 x i64
                .raw(0x0B);             // end
        Buf code = new Buf().vec(1).uleb(localsAndCode.toBytes().length).buf(localsAndCode);

        WasmBuilder b = new WasmBuilder()
                .section(1, typeVoidVoid())
                .section(3, new Buf().vec(1).uleb(0))
                .section(10, code);

        Module m = Module.parse(b.build());
        ValType[] locals = m.code().get(0).locals();
        assertArrayEquals(new ValType[] {ValType.I32, ValType.I32, ValType.I64}, locals);
    }

    @Test
    void importsAndExports() {
        WasmBuilder b = new WasmBuilder()
                .section(1, typeVoidVoid())
                // import billboard.log (func, type 0)
                .section(2, new Buf().vec(1)
                        .name("billboard").name("log").raw(0x00).uleb(0))
                // one defined function (type 0), function index 1
                .section(3, new Buf().vec(1).uleb(0))
                // one memory, min 1
                .section(5, new Buf().vec(1).raw(0x00, 0x01))
                // exports: memory[0]="memory", func[1]="run"
                .section(7, new Buf().vec(2)
                        .name("memory").raw(0x02).uleb(0)
                        .name("run").raw(0x00).uleb(1))
                .section(10, codeSection(new Buf().raw(0x0B)));

        Module m = Module.parse(b.build());

        assertEquals(1, m.imports().size());
        Import imp = m.imports().get(0);
        assertEquals("billboard", imp.module());
        assertEquals("log", imp.name());
        assertEquals(ExternalKind.FUNCTION, imp.kind());
        assertEquals(new Import.Func(0), imp.descriptor());

        assertEquals(1, m.importedFunctionCount());
        assertEquals(2, m.functionCount());

        Map<String, Export> byName = new java.util.HashMap<>();
        for (Export e : m.exports()) {
            byName.put(e.name(), e);
        }
        assertEquals(new Export("memory", ExternalKind.MEMORY, 0), byName.get("memory"));
        assertEquals(new Export("run", ExternalKind.FUNCTION, 1), byName.get("run"));

        assertEquals(1, m.memories().size());
        assertEquals(new Limits(1, -1), m.memories().get(0));
    }

    @Test
    void activeAndPassiveData() {
        WasmBuilder b = new WasmBuilder()
                .section(5, new Buf().vec(1).raw(0x00, 0x01)) // memory min 1
                .section(12, new Buf().uleb(2))               // DataCount = 2
                .section(11, new Buf().vec(2)
                        // seg0: active, offset i32.const 0, bytes "abc"
                        .uleb(0).raw(0x41, 0x00, 0x0B).uleb(3).raw(0x61, 0x62, 0x63)
                        // seg1: passive, bytes 0xDE 0xAD
                        .uleb(1).uleb(2).raw(0xDE, 0xAD));

        Module m = Module.parse(b.build());
        assertEquals(2, m.dataCount());
        assertEquals(2, m.datas().size());

        DataSegment s0 = m.datas().get(0);
        assertEquals(DataSegment.Mode.ACTIVE, s0.mode());
        assertEquals(0, s0.memoryIndex());
        assertNotNull(s0.offset());
        assertArrayEquals(new byte[] {0x41, 0x00}, s0.offset().bytes());
        assertArrayEquals(new byte[] {0x61, 0x62, 0x63}, s0.data());

        DataSegment s1 = m.datas().get(1);
        assertEquals(DataSegment.Mode.PASSIVE, s1.mode());
        assertNull(s1.offset());
        assertArrayEquals(new byte[] {(byte) 0xDE, (byte) 0xAD}, s1.data());
    }

    @Test
    void globalsMutableAndImmutable() {
        WasmBuilder b = new WasmBuilder()
                .section(6, new Buf().vec(2)
                        // g0: i32, mutable, init i32.const 42
                        .raw(0x7F, 0x01).raw(0x41, 0x2A, 0x0B)
                        // g1: i64, immutable, init i64.const 7
                        .raw(0x7E, 0x00).raw(0x42, 0x07, 0x0B));

        Module m = Module.parse(b.build());
        assertEquals(2, m.globals().size());

        Global g0 = m.globals().get(0);
        assertEquals(ValType.I32, g0.type().valueType());
        assertTrue(g0.type().mutable());
        assertArrayEquals(new byte[] {0x41, 0x2A}, g0.init().bytes());

        Global g1 = m.globals().get(1);
        assertEquals(ValType.I64, g1.type().valueType());
        assertFalse(g1.type().mutable());
        assertArrayEquals(new byte[] {0x42, 0x07}, g1.init().bytes());
    }

    @Test
    void tableAndElementSegment() {
        WasmBuilder b = new WasmBuilder()
                .section(1, typeVoidVoid())
                .section(3, new Buf().vec(1).uleb(0))
                // table: funcref, min 1
                .section(4, new Buf().vec(1).raw(0x70, 0x00, 0x01))
                // elem: active, offset i32.const 0, funcidx [0]
                .section(9, new Buf().vec(1)
                        .uleb(0).raw(0x41, 0x00, 0x0B).vec(1).uleb(0))
                .section(10, codeSection(new Buf().raw(0x0B)));

        Module m = Module.parse(b.build());
        assertEquals(1, m.tables().size());
        assertEquals(ValType.FUNCREF, m.tables().get(0).elementType());
        assertEquals(new Limits(1, -1), m.tables().get(0).limits());

        assertEquals(1, m.elements().size());
        ElementSegment e = m.elements().get(0);
        assertEquals(ElementSegment.Mode.ACTIVE, e.mode());
        assertEquals(0, e.tableIndex());
        assertTrue(e.isFunctionIndexForm());
        assertArrayEquals(new int[] {0}, e.functionIndices());
        assertNotNull(e.offset());
    }

    @Test
    void blockTypeVariants() {
        // types: 0 = () -> (), 1 = (i32) -> (i32)
        Buf types = new Buf().vec(2)
                .raw(0x60, 0x00, 0x00)
                .raw(0x60, 0x01, 0x7F, 0x01, 0x7F);
        // body: block(empty) end ; block(i32) i32.const 0 end ; block(type 1) end ; end
        Buf instr = new Buf().raw(
                0x02, 0x40, 0x0B,
                0x02, 0x7F, 0x41, 0x00, 0x0B,
                0x02, 0x01, 0x0B,
                0x0B);

        WasmBuilder b = new WasmBuilder()
                .section(1, types)
                .section(3, new Buf().vec(1).uleb(0))
                .section(10, codeSection(instr));

        Module m = Module.parse(b.build());
        SideTable st = m.code().get(0).sideTable();

        SideTable.Block empty = st.block(0);
        assertEquals(SideTable.BlockKind.BLOCK, empty.kind());
        assertEquals(0, empty.paramCount());
        assertEquals(0, empty.resultCount());
        assertEquals(3, empty.endPc());

        SideTable.Block valtype = st.block(3);
        assertEquals(0, valtype.paramCount());
        assertEquals(1, valtype.resultCount());
        assertEquals(8, valtype.endPc());

        SideTable.Block typeIndexed = st.block(8);
        assertEquals(1, typeIndexed.paramCount());
        assertEquals(1, typeIndexed.resultCount());
        assertEquals(11, typeIndexed.endPc());
    }

    @Test
    void callIndirectReferenceTypesEncoding() {
        // call_indirect type 0, table 0 (LEB table index, not a reserved 0x00 form)
        Buf instr = new Buf().raw(0x41, 0x00, 0x11, 0x00, 0x00, 0x0B);

        WasmBuilder b = new WasmBuilder()
                .section(1, typeVoidVoid())
                .section(3, new Buf().vec(1).uleb(0))
                .section(4, new Buf().vec(1).raw(0x70, 0x00, 0x01)) // a funcref table
                .section(10, codeSection(instr));

        Module m = Module.parse(b.build());
        assertEquals(1, m.functionCount());
        assertEquals(1, m.tables().size());
    }

    @Test
    void signExtensionOpcodes() {
        // i32.extend8_s/16_s, i64.extend8_s/16_s/32_s (0xC0..0xC4), then end
        Buf instr = new Buf().raw(0xC0, 0xC1, 0xC2, 0xC3, 0xC4, 0x0B);

        WasmBuilder b = new WasmBuilder()
                .section(1, typeVoidVoid())
                .section(3, new Buf().vec(1).uleb(0))
                .section(10, codeSection(instr));

        assertEquals(1, Module.parse(b.build()).code().size());
    }

    @Test
    void fcPrefixedOpcodes() {
        // trunc_sat 0..7, then memory.init/data.drop/memory.copy/memory.fill
        Buf instr = new Buf()
                .raw(0xFC, 0x00).raw(0xFC, 0x01).raw(0xFC, 0x02).raw(0xFC, 0x03)
                .raw(0xFC, 0x04).raw(0xFC, 0x05).raw(0xFC, 0x06).raw(0xFC, 0x07)
                .raw(0xFC, 0x08, 0x00, 0x00) // memory.init data=0 mem=0
                .raw(0xFC, 0x09, 0x00)       // data.drop data=0
                .raw(0xFC, 0x0A, 0x00, 0x00) // memory.copy
                .raw(0xFC, 0x0B, 0x00)       // memory.fill
                .raw(0x0B);

        Buf codeBody = new Buf().vec(0).buf(instr);
        WasmBuilder b = new WasmBuilder()
                .section(1, typeVoidVoid())
                .section(3, new Buf().vec(1).uleb(0))
                .section(5, new Buf().vec(1).raw(0x00, 0x01))  // memory
                .section(12, new Buf().uleb(1))                // DataCount = 1
                .section(10, new Buf().vec(1).uleb(codeBody.toBytes().length).buf(codeBody))
                .section(11, new Buf().vec(1).uleb(1).uleb(0)); // 1 passive data segment (empty)

        Module m = Module.parse(b.build());
        assertEquals(1, m.code().size());
        assertEquals(1, m.dataCount());
        assertEquals(1, m.datas().size());
    }

    @Test
    void startSection() {
        WasmBuilder b = new WasmBuilder()
                .section(1, typeVoidVoid())
                .section(3, new Buf().vec(1).uleb(0))
                .section(8, new Buf().uleb(0)) // start = func 0
                .section(10, codeSection(new Buf().raw(0x0B)));

        Module m = Module.parse(b.build());
        assertTrue(m.startFunction().isPresent());
        assertEquals(0, m.startFunction().getAsInt());
    }
}
