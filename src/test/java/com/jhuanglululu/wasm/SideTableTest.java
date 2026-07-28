package com.jhuanglululu.wasm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.jhuanglululu.wasm.WasmBuilder.Buf;
import org.junit.jupiter.api.Test;

/**
 * Branch-sidetable correctness. Every expected offset is hand-computed from the
 * instruction layout written in each test (offsets are 0-based into the function
 * body, i.e. the first instruction after the locals declaration).
 */
class SideTableTest {

    /** Builds a single-function module; {@code types} is the full type-section body. */
    private static Module module(Buf types, int funcTypeIndex, Buf instructions) {
        Buf body = new Buf().vec(0).buf(instructions); // locals count 0
        Buf code = new Buf().vec(1).uleb(body.toBytes().length).buf(body);
        return Module.parse(new WasmBuilder()
                .section(1, types)
                .section(3, new Buf().vec(1).uleb(funcTypeIndex))
                .section(10, code)
                .build());
    }

    private static Buf typeVoidVoid() {
        return new Buf().vec(1).raw(0x60, 0x00, 0x00);
    }

    @Test
    void nestedBlockLoopIfElse() {
        // Offsets:
        //  0: block(empty)  2: br 0     4: end(block)
        //  5: loop(empty)   7: br 0     9: end(loop)
        // 10: if(empty)    12: nop     13: else   14: nop   15: end(if)
        // 16: end(function)
        Buf instr = new Buf().raw(
                0x02, 0x40, 0x0C, 0x00, 0x0B,
                0x03, 0x40, 0x0C, 0x00, 0x0B,
                0x04, 0x40, 0x01, 0x05, 0x01, 0x0B,
                0x0B);

        SideTable st = module(typeVoidVoid(), 0, instr).code().get(0).sideTable();

        SideTable.Block block = st.block(0);
        assertEquals(SideTable.BlockKind.BLOCK, block.kind());
        assertEquals(-1, block.elsePc());
        assertEquals(5, block.endPc());
        assertEquals(0, block.paramCount());
        assertEquals(0, block.resultCount());

        SideTable.Block loop = st.block(5);
        assertEquals(SideTable.BlockKind.LOOP, loop.kind());
        assertEquals(-1, loop.elsePc());
        assertEquals(10, loop.endPc());

        SideTable.Block iff = st.block(10);
        assertEquals(SideTable.BlockKind.IF, iff.kind());
        assertEquals(14, iff.elsePc());   // first instruction after `else`
        assertEquals(16, iff.endPc());

        // br at offset 2 targets the enclosing block -> its end (offset 5), keep 0.
        SideTable.Branch brBlock = st.branch(2);
        assertArrayEquals(new int[] {5}, brBlock.targetPc());
        assertArrayEquals(new int[] {0}, brBlock.keep());

        // br at offset 7 targets the enclosing loop -> its body start (offset 7), keep 0.
        SideTable.Branch brLoop = st.branch(7);
        assertArrayEquals(new int[] {7}, brLoop.targetPc());
        assertArrayEquals(new int[] {0}, brLoop.keep());

        assertNull(st.branch(0)); // block opcode is not a branch
    }

    @Test
    void brTableResolvesEveryTarget() {
        // Three nested blocks; innermost has `br_table 0 1 (default 2)`.
        //  0: block A   2: block B   4: block C
        //  6: i32.const 0   8: br_table  9: count=2  10: L0  11: L1  12: default=2
        // 13: end C   14: end B   15: end A   16: end func
        Buf instr = new Buf().raw(
                0x02, 0x40, 0x02, 0x40, 0x02, 0x40,
                0x41, 0x00,
                0x0E, 0x02, 0x00, 0x01, 0x02,
                0x0B, 0x0B, 0x0B, 0x0B);

        SideTable st = module(typeVoidVoid(), 0, instr).code().get(0).sideTable();

        // C ends at 14, B at 15, A at 16 (offset after each matching `end`).
        assertEquals(14, st.block(4).endPc());
        assertEquals(15, st.block(2).endPc());
        assertEquals(16, st.block(0).endPc());

        SideTable.Branch brt = st.branch(8);
        // labels [0,1] then default 2 -> block C(14), B(15), A(16).
        assertArrayEquals(new int[] {14, 15, 16}, brt.targetPc());
        assertArrayEquals(new int[] {0, 0, 0}, brt.keep());
    }

    @Test
    void loopBackEdgeKeepsBlockParameters() {
        // types: 0 = () -> (), 1 = (i32) -> (i32)
        Buf types = new Buf().vec(2)
                .raw(0x60, 0x00, 0x00)
                .raw(0x60, 0x01, 0x7F, 0x01, 0x7F);
        //  0: i32.const 5   2: loop(type 1)   4: br 0   6: end(loop)   7: drop   8: end func
        Buf instr = new Buf().raw(
                0x41, 0x05,
                0x03, 0x01,
                0x0C, 0x00,
                0x0B,
                0x1A,
                0x0B);

        SideTable st = module(types, 0, instr).code().get(0).sideTable();

        SideTable.Block loop = st.block(2);
        assertEquals(SideTable.BlockKind.LOOP, loop.kind());
        assertEquals(1, loop.paramCount());
        assertEquals(1, loop.resultCount());
        assertEquals(7, loop.endPc());

        // br 0 is a loop back-edge: target = loop body start (offset 4), keep = params (1).
        SideTable.Branch br = st.branch(4);
        assertArrayEquals(new int[] {4}, br.targetPc());
        assertArrayEquals(new int[] {1}, br.keep());
    }

    @Test
    void branchToFunctionLevelReturnsToBodyEnd() {
        // types: 0 = () -> (i32)
        Buf types = new Buf().vec(1).raw(0x60, 0x00, 0x01, 0x7F);
        //  0: block(empty)  2: br 1 (function level)  4: end(block)  5: i32.const 0  7: end func
        Buf instr = new Buf().raw(
                0x02, 0x40,
                0x0C, 0x01,
                0x0B,
                0x41, 0x00,
                0x0B);

        SideTable st = module(types, 0, instr).code().get(0).sideTable();

        // br 1 skips the block and targets the implicit function label: body end (8),
        // keeping the function's single result.
        SideTable.Branch br = st.branch(2);
        assertArrayEquals(new int[] {8}, br.targetPc());
        assertArrayEquals(new int[] {1}, br.keep());
    }
}
