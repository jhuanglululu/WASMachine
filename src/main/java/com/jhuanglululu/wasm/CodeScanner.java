package com.jhuanglululu.wasm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Single-pass decoder over one function body. It has three jobs, all done in one
 * forward walk with no runtime cost deferred:
 * <ol>
 *   <li>reject any instruction that uses a gated feature (SIMD, atomics, exception
 *       handling, tail calls, multi-memory, GC), naming the feature;</li>
 *   <li>bounds-check every index immediate against the module's index spaces;</li>
 *   <li>build the {@link SideTable} — matching structured control instructions and
 *       resolving every branch target (see {@link SideTable} for the format and the
 *       back-patching approach).</li>
 * </ol>
 * No operand-stack type checking is performed (see {@link Module} for the validation
 * contract); only structure and declared block arity are needed here.
 */
final class CodeScanner {

    // Counts of the module's index spaces, known before the code section is parsed
    // (the section ordering guarantees this — DataCount precedes Code specifically
    // so data indices can be checked here).
    private final List<FuncType> types;
    private final int functionCount;
    private final int globalCount;
    private final int tableCount;
    private final int memoryCount;
    private final int elementCount;
    private final int dataCount; // -1 if no DataCount section

    private final FuncType funcType;
    private final int localCount;
    private final byte[] body;
    private final WasmReader r;

    private final Map<Integer, SideTable.Block> blocks = new HashMap<>();
    private final Map<Integer, SideTable.Branch> branches = new HashMap<>();

    /** An open structured region during the structural pass. */
    private static final class Frame {
        final SideTable.BlockKind kind;
        final int startPc;      // offset of the block/loop/if opcode; -1 for the function frame
        final int paramCount;
        final int resultCount;
        final int resolvedTarget; // branch target if known now (loop body / function end), else -1
        final int keep;           // operands a branch to this label keeps
        int elsePc = -1;
        final List<Patch> pending = new ArrayList<>(); // forward branches awaiting endPc

        Frame(SideTable.BlockKind kind, int startPc, int paramCount, int resultCount,
                int resolvedTarget, int keep) {
            this.kind = kind;
            this.startPc = startPc;
            this.paramCount = paramCount;
            this.resultCount = resultCount;
            this.resolvedTarget = resolvedTarget;
            this.keep = keep;
        }
    }

    /** A branch-target slot to fill with a block's {@code endPc} once it is known. */
    @SuppressWarnings("ArrayRecordComponent") // identity equality is fine; parse-time scratch
    private record Patch(int[] targetArray, int slot) {}

    CodeScanner(List<FuncType> types, int functionCount, int globalCount, int tableCount,
            int memoryCount, int elementCount, int dataCount,
            FuncType funcType, int localCount, byte[] body) {
        this.types = types;
        this.functionCount = functionCount;
        this.globalCount = globalCount;
        this.tableCount = tableCount;
        this.memoryCount = memoryCount;
        this.elementCount = elementCount;
        this.dataCount = dataCount;
        this.funcType = funcType;
        this.localCount = localCount;
        this.body = body;
        this.r = new WasmReader(body);
    }

    SideTable scan() {
        Deque<Frame> ctrl = new ArrayDeque<>();
        // Implicit function-level label: a branch to it (or `return`) leaves the
        // function, keeping the function's result values. Its target is the end of
        // the body.
        ctrl.push(new Frame(SideTable.BlockKind.BLOCK, -1, 0, funcType.results().size(),
                body.length, funcType.results().size()));

        while (r.hasRemaining()) {
            int opPc = r.position();
            int op = r.readByte();
            switch (op) {
                case 0x00 -> { }                 // unreachable
                case 0x01 -> { }                 // nop
                case 0x02, 0x03, 0x04 -> {        // block / loop / if
                    int[] arity = readBlockType();
                    int bodyPc = r.position();
                    SideTable.BlockKind kind = op == 0x02 ? SideTable.BlockKind.BLOCK
                            : op == 0x03 ? SideTable.BlockKind.LOOP
                            : SideTable.BlockKind.IF;
                    // A branch to a loop targets its body start (a back-edge) and keeps
                    // its parameters; a branch to a block/if targets its (not-yet-known)
                    // end and keeps its results.
                    int resolved = kind == SideTable.BlockKind.LOOP ? bodyPc : -1;
                    int keep = kind == SideTable.BlockKind.LOOP ? arity[0] : arity[1];
                    ctrl.push(new Frame(kind, opPc, arity[0], arity[1], resolved, keep));
                }
                case 0x05 -> {                    // else
                    Frame f = ctrl.peek();
                    if (f == null || f.kind != SideTable.BlockKind.IF || f.elsePc != -1) {
                        throw new WasmParseException("unexpected `else` at offset " + opPc);
                    }
                    f.elsePc = r.position();
                }
                case 0x0B -> {                    // end
                    Frame f = ctrl.pop();
                    int endPc = r.position();
                    for (Patch p : f.pending) {
                        p.targetArray()[p.slot()] = endPc;
                    }
                    if (f.startPc >= 0) {
                        // -1 elsePc means "no else": interpreter falls through to end.
                        blocks.put(f.startPc, new SideTable.Block(
                                f.kind, f.elsePc, endPc, f.paramCount, f.resultCount));
                    } else if (r.hasRemaining()) {
                        throw new WasmParseException(
                                "trailing bytes after function body end at offset " + endPc);
                    }
                }
                case 0x0C, 0x0D -> {              // br / br_if
                    int depth = r.readU32();
                    int[] target = new int[1];
                    int[] keep = new int[1];
                    resolveLabel(ctrl, depth, opPc, target, keep, 0);
                    branches.put(opPc, new SideTable.Branch(target, keep));
                }
                case 0x0E -> {                    // br_table
                    int n = r.readU32();
                    // Bounded by remaining input (each label is at least one byte), using an
                    // unsigned check so a bit-31 count cannot pass or overflow new int[n + 1].
                    if (Integer.compareUnsigned(n, r.remaining()) > 0) {
                        throw new WasmParseException("br_table count " + Integer.toUnsignedString(n)
                                + " exceeds input at offset " + opPc);
                    }
                    int[] target = new int[n + 1];
                    int[] keep = new int[n + 1];
                    for (int i = 0; i < n; i++) {
                        int depth = r.readU32();
                        resolveLabel(ctrl, depth, opPc, target, keep, i);
                    }
                    int def = r.readU32();
                    resolveLabel(ctrl, def, opPc, target, keep, n);
                    branches.put(opPc, new SideTable.Branch(target, keep));
                }
                case 0x0F -> { }                  // return (interpreter unwinds to the function frame)
                case 0x10 -> {                    // call
                    int f = r.readU32();
                    if (outOfRange(f, functionCount)) {
                        throw new WasmParseException("call target " + Integer.toUnsignedString(f)
                                + " out of range at offset " + opPc);
                    }
                }
                case 0x11 -> {                    // call_indirect (reference-types encoding: LEB table index)
                    int typeIdx = r.readU32();
                    if (outOfRange(typeIdx, types.size())) {
                        throw new WasmParseException(
                                "call_indirect type " + Integer.toUnsignedString(typeIdx) + " out of range at offset " + opPc);
                    }
                    int tableIdx = r.readU32();
                    if (outOfRange(tableIdx, tableCount)) {
                        throw new WasmParseException(
                                "call_indirect table " + Integer.toUnsignedString(tableIdx) + " out of range at offset " + opPc);
                    }
                }
                case 0x1A -> { }                  // drop
                case 0x1B -> { }                  // select
                case 0x1C -> {                    // select t*  (typed select)
                    int n = r.readU32();
                    if (Integer.compareUnsigned(n, r.remaining()) > 0) {
                        throw new WasmParseException("select type count " + Integer.toUnsignedString(n)
                                + " exceeds input at offset " + opPc);
                    }
                    for (int i = 0; i < n; i++) {
                        ValType.fromByte(r.readByte(), r.position() - 1);
                    }
                }
                case 0x20, 0x21, 0x22 -> {        // local.get / set / tee
                    int idx = r.readU32();
                    if (outOfRange(idx, localCount)) {
                        throw new WasmParseException("local index " + Integer.toUnsignedString(idx)
                                + " out of range at offset " + opPc);
                    }
                }
                case 0x23, 0x24 -> {              // global.get / set
                    int idx = r.readU32();
                    if (outOfRange(idx, globalCount)) {
                        throw new WasmParseException("global index " + Integer.toUnsignedString(idx)
                                + " out of range at offset " + opPc);
                    }
                }
                case 0x25, 0x26 -> {              // table.get / set
                    int idx = r.readU32();
                    if (outOfRange(idx, tableCount)) {
                        throw new WasmParseException("table index " + Integer.toUnsignedString(idx)
                                + " out of range at offset " + opPc);
                    }
                }
                case 0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F,
                     0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37,
                     0x38, 0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E -> readMemArg(opPc); // loads/stores
                case 0x3F, 0x40 -> {              // memory.size / memory.grow
                    int mem = r.readByte();
                    if (mem != 0) {
                        throw new WasmParseException(
                                "unsupported feature multi-memory (memory index " + mem + ") at offset " + opPc);
                    }
                    if (memoryCount == 0) {
                        throw new WasmParseException("memory instruction without a memory at offset " + opPc);
                    }
                }
                case 0x41 -> r.readS32();         // i32.const
                case 0x42 -> r.readS64();         // i64.const
                case 0x43 -> r.readF32Bits();     // f32.const
                case 0x44 -> r.readF64Bits();     // f64.const
                case 0xD0 -> ValType.fromByte(r.readByte(), r.position() - 1); // ref.null t
                case 0xD1 -> { }                  // ref.is_null
                case 0xD2 -> {                    // ref.func
                    int f = r.readU32();
                    if (outOfRange(f, functionCount)) {
                        throw new WasmParseException("ref.func target " + Integer.toUnsignedString(f)
                                + " out of range at offset " + opPc);
                    }
                }
                case 0xFC -> readFcPrefixed(opPc);
                // --- gated features, named ---
                case 0x06, 0x07, 0x08, 0x09, 0x0A, 0x18, 0x19, 0x1F -> throw new WasmParseException(
                        "unsupported feature exception handling (opcode 0x" + Integer.toHexString(op)
                                + ") at offset " + opPc);
                case 0x12, 0x13 -> throw new WasmParseException(
                        "unsupported feature tail call (opcode 0x" + Integer.toHexString(op)
                                + ") at offset " + opPc);
                case 0xFD -> throw new WasmParseException(
                        "unsupported feature SIMD (0xFD prefix) at offset " + opPc);
                case 0xFE -> throw new WasmParseException(
                        "unsupported feature atomics (0xFE prefix) at offset " + opPc);
                case 0xFB -> throw new WasmParseException(
                        "unsupported feature GC (0xFB prefix) at offset " + opPc);
                default -> {
                    if (op >= 0x45 && op <= 0xC4) {
                        // Numeric comparisons, arithmetic, conversions, sign-extension —
                        // all have no immediates.
                        break;
                    }
                    throw new WasmParseException(
                            "invalid or unsupported opcode 0x" + Integer.toHexString(op) + " at offset " + opPc);
                }
            }
        }
        if (!ctrl.isEmpty()) {
            throw new WasmParseException("unexpected end of function body: missing `end`");
        }
        return new SideTable(blocks, branches);
    }

    /** Resolves label {@code depth} to a jump target + keep count, filling slot {@code i}. */
    private void resolveLabel(Deque<Frame> ctrl, int depth, int opPc,
            int[] target, int[] keep, int i) {
        if (outOfRange(depth, ctrl.size())) {
            throw new WasmParseException(
                    "branch label " + Integer.toUnsignedString(depth) + " out of range at offset " + opPc);
        }
        // ArrayDeque iterates from head (top of stack, depth 0) toward the tail.
        Frame f = null;
        int d = 0;
        for (Frame frame : ctrl) {
            if (d == depth) {
                f = frame;
                break;
            }
            d++;
        }
        keep[i] = f.keep;
        if (f.resolvedTarget >= 0) {
            target[i] = f.resolvedTarget;
        } else {
            // Forward branch to a block/if: back-patch when its `end` is reached.
            f.pending.add(new Patch(target, i));
        }
    }

    private int[] readBlockType() {
        int offset = r.position();
        long bt = r.readS33();
        if (bt >= 0) {
            if (bt >= types.size()) {
                throw new WasmParseException(
                        "block type index " + bt + " out of range at offset " + offset);
            }
            FuncType t = types.get((int) bt);
            return new int[] {t.params().size(), t.results().size()};
        }
        if (bt == -64) { // 0x40: empty block type
            return new int[] {0, 0};
        }
        // Negative, not -64: a single-valtype result. Validate (rejects v128/GC).
        ValType.fromByte((int) (bt & 0x7F), offset);
        return new int[] {0, 1};
    }

    private void readMemArg(int opPc) {
        int align = r.readU32();
        if ((align & 0x40) != 0) {
            throw new WasmParseException(
                    "unsupported feature multi-memory (memarg with memory index) at offset " + opPc);
        }
        r.readU32(); // offset
        if (memoryCount == 0) {
            throw new WasmParseException("memory instruction without a memory at offset " + opPc);
        }
    }

    private void readFcPrefixed(int opPc) {
        int sub = r.readU32();
        switch (sub) {
            case 0, 1, 2, 3, 4, 5, 6, 7 -> { } // trunc_sat_f{32,64}_{s,u}: no immediate
            case 8 -> {                        // memory.init dataidx, memidx
                int dataIdx = r.readU32();
                requireDataCount(opPc, "memory.init");
                if (outOfRange(dataIdx, dataCount)) {
                    throw new WasmParseException("data index " + Integer.toUnsignedString(dataIdx)
                            + " out of range at offset " + opPc);
                }
                requireZeroMem(r.readByte(), opPc);
            }
            case 9 -> {                        // data.drop dataidx
                int dataIdx = r.readU32();
                requireDataCount(opPc, "data.drop");
                if (outOfRange(dataIdx, dataCount)) {
                    throw new WasmParseException("data index " + Integer.toUnsignedString(dataIdx)
                            + " out of range at offset " + opPc);
                }
            }
            case 10 -> {                       // memory.copy dst, src
                requireZeroMem(r.readByte(), opPc);
                requireZeroMem(r.readByte(), opPc);
                requireMemory(opPc);
            }
            case 11 -> {                       // memory.fill mem
                requireZeroMem(r.readByte(), opPc);
                requireMemory(opPc);
            }
            case 12 -> {                       // table.init elemidx, tableidx
                int elemIdx = r.readU32();
                if (outOfRange(elemIdx, elementCount)) {
                    throw new WasmParseException("element index " + Integer.toUnsignedString(elemIdx)
                            + " out of range at offset " + opPc);
                }
                requireTable(r.readU32(), opPc);
            }
            case 13 -> {                       // elem.drop elemidx
                int elemIdx = r.readU32();
                if (outOfRange(elemIdx, elementCount)) {
                    throw new WasmParseException("element index " + Integer.toUnsignedString(elemIdx)
                            + " out of range at offset " + opPc);
                }
            }
            case 14 -> {                       // table.copy dst, src
                requireTable(r.readU32(), opPc);
                requireTable(r.readU32(), opPc);
            }
            case 15, 16, 17 -> requireTable(r.readU32(), opPc); // table.grow / size / fill
            default -> throw new WasmParseException(
                    "invalid or unsupported 0xFC opcode " + sub + " at offset " + opPc);
        }
    }

    private void requireDataCount(int opPc, String op) {
        if (dataCount < 0) {
            throw new WasmParseException(op + " requires a DataCount section at offset " + opPc);
        }
    }

    private void requireZeroMem(int mem, int opPc) {
        if (mem != 0) {
            throw new WasmParseException(
                    "unsupported feature multi-memory (memory index " + mem + ") at offset " + opPc);
        }
    }

    private void requireMemory(int opPc) {
        if (memoryCount == 0) {
            throw new WasmParseException("memory instruction without a memory at offset " + opPc);
        }
    }

    private void requireTable(int tableIdx, int opPc) {
        if (outOfRange(tableIdx, tableCount)) {
            throw new WasmParseException("table index " + tableIdx + " out of range at offset " + opPc);
        }
    }

    /**
     * True if {@code index} (interpreted as an unsigned 32-bit immediate) is not less than
     * {@code count}. Immediates are decoded into a signed {@code int}, so a value with bit
     * 31 set is negative and would spuriously pass a signed {@code >=} check — every
     * index/label bound must use this instead.
     */
    private static boolean outOfRange(int index, int count) {
        return Integer.compareUnsigned(index, count) >= 0;
    }
}
