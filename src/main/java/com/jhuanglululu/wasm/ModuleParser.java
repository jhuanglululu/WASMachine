package com.jhuanglululu.wasm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Drives a single {@link Module#parse(byte[])}. One instance parses one module;
 * it accumulates each section's contents and, after the section loop, runs the
 * cross-section validation the individual sections could not (multi-memory,
 * code/function count agreement, data-count agreement).
 */
final class ModuleParser {

    /** Cap on declared locals per function, to bound allocation against a hostile count. */
    private static final int MAX_LOCALS = 1 << 20;

    /**
     * Max linear-memory pages we accept. The spec cap is 65536 pages (4 GiB), but a
     * memory is backed by a Java {@code byte[]}, so the real limit is the largest page
     * count whose byte size fits an array ({@code Integer.MAX_VALUE / 65536} = 32767).
     * Enforcing it at parse prevents {@code min * 65536} from overflowing at instantiate.
     */
    private static final long MAX_MEMORY_PAGES = Integer.MAX_VALUE / 65536;

    /**
     * Max table entries we accept. rustc emits tiny tables (a handful of entries), so a
     * generous 2^22 cap rejects hostile sizes while never constraining real output; it
     * also bounds the {@code int[]} a table allocates and the growth ceiling.
     */
    static final int MAX_TABLE_ENTRIES = 1 << 22;

    private static final byte[] MAGIC = {0x00, 0x61, 0x73, 0x6D};
    private static final byte[] VERSION = {0x01, 0x00, 0x00, 0x00};

    // Canonical section ordering rank by section id (custom = 0 is excluded and may
    // appear anywhere). DataCount (12) sits between Element (9) and Code (10).
    private static final int[] SECTION_RANK = new int[13];

    static {
        SECTION_RANK[1] = 1;   // type
        SECTION_RANK[2] = 2;   // import
        SECTION_RANK[3] = 3;   // function
        SECTION_RANK[4] = 4;   // table
        SECTION_RANK[5] = 5;   // memory
        SECTION_RANK[6] = 6;   // global
        SECTION_RANK[7] = 7;   // export
        SECTION_RANK[8] = 8;   // start
        SECTION_RANK[9] = 9;   // element
        SECTION_RANK[12] = 10; // data count
        SECTION_RANK[10] = 11; // code
        SECTION_RANK[11] = 12; // data
    }

    private final WasmReader r;

    private final List<FuncType> types = new ArrayList<>();
    private final List<Import> imports = new ArrayList<>();
    private final List<Integer> functionTypeIndices = new ArrayList<>();
    private final List<TableType> tables = new ArrayList<>();
    private final List<Limits> memories = new ArrayList<>();
    private final List<Global> globals = new ArrayList<>();
    private final List<Export> exports = new ArrayList<>();
    private final List<ElementSegment> elements = new ArrayList<>();
    private final List<DataSegment> datas = new ArrayList<>();
    private final List<FunctionCode> code = new ArrayList<>();

    private int importedFunctionCount;
    private int importedGlobalCount;
    private int importedTableCount;
    private int importedMemoryCount;
    private int startFunction = -1;
    private int dataCount = -1;

    private boolean codeSectionSeen;

    ModuleParser(byte[] bytes) {
        this.r = new WasmReader(bytes);
    }

    Module parse() {
        readPreamble();

        int lastRank = 0;
        while (r.hasRemaining()) {
            int id = r.readByte();
            int size = r.readU32();
            if (Integer.toUnsignedLong(size) > r.remaining()) {
                throw new WasmParseException("section " + id + " length " + Integer.toUnsignedLong(size)
                        + " exceeds remaining input (" + r.remaining() + " bytes)");
            }
            WasmReader sec = r.subReader(size);

            if (id == 0) {
                sec.readName(); // validate the custom section name; ignore the payload
                continue;
            }
            if (id > 12) {
                throw new WasmParseException("invalid section id " + id);
            }
            int rank = SECTION_RANK[id];
            if (rank <= lastRank) {
                throw new WasmParseException("section id " + id + " is out of order or duplicated");
            }
            lastRank = rank;

            switch (id) {
                case 1 -> parseTypes(sec);
                case 2 -> parseImports(sec);
                case 3 -> parseFunctions(sec);
                case 4 -> parseTables(sec);
                case 5 -> parseMemories(sec);
                case 6 -> parseGlobals(sec);
                case 7 -> parseExports(sec);
                case 8 -> parseStart(sec);
                case 9 -> parseElements(sec);
                case 10 -> parseCode(sec);
                case 11 -> parseData(sec);
                case 12 -> parseDataCount(sec);
                default -> throw new WasmParseException("invalid section id " + id);
            }
            if (sec.hasRemaining()) {
                throw new WasmParseException("section id " + id + " has " + sec.remaining()
                        + " trailing byte(s)");
            }
        }

        finalValidation();

        int[] funcTypeIdx = new int[functionTypeIndices.size()];
        for (int i = 0; i < funcTypeIdx.length; i++) {
            funcTypeIdx[i] = functionTypeIndices.get(i);
        }
        return new Module(types, imports, importedFunctionCount, funcTypeIdx, tables, memories,
                globals, exports, elements, datas, code, startFunction, dataCount);
    }

    private void readPreamble() {
        byte[] magic = r.readBytes(4);
        if (!java.util.Arrays.equals(magic, MAGIC)) {
            throw new WasmParseException("not a WebAssembly module: bad magic");
        }
        byte[] version = r.readBytes(4);
        if (!java.util.Arrays.equals(version, VERSION)) {
            throw new WasmParseException("unsupported WebAssembly version "
                    + (version[0] & 0xFF) + "." + (version[1] & 0xFF));
        }
    }

    // --- index-space sizes (valid once the relevant sections are parsed) ---

    private int functionCount() {
        return functionTypeIndices.size();
    }

    private int globalCount() {
        return importedGlobalCount + globals.size();
    }

    private int tableCount() {
        return importedTableCount + tables.size();
    }

    private int memoryCount() {
        return importedMemoryCount + memories.size();
    }

    // --- sections ---

    private void parseTypes(WasmReader sec) {
        int n = sec.readVecCount();
        for (int i = 0; i < n; i++) {
            int tag = sec.readByte();
            if (tag != 0x60) {
                throw new WasmParseException("invalid function type tag 0x" + Integer.toHexString(tag)
                        + " at offset " + (sec.position() - 1));
            }
            List<ValType> params = readValTypeVec(sec);
            List<ValType> results = readValTypeVec(sec);
            types.add(new FuncType(params, results));
        }
    }

    private List<ValType> readValTypeVec(WasmReader sec) {
        int n = sec.readVecCount();
        List<ValType> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(ValType.fromByte(sec.readByte(), sec.position() - 1));
        }
        return out;
    }

    private void parseImports(WasmReader sec) {
        int n = sec.readVecCount();
        for (int i = 0; i < n; i++) {
            String module = sec.readName();
            String name = sec.readName();
            int kind = sec.readByte();
            switch (kind) {
                case 0x00 -> {
                    int typeIdx = sec.readU32();
                    checkTypeIndex(typeIdx, sec.position());
                    imports.add(new Import(module, name, new Import.Func(typeIdx)));
                    functionTypeIndices.add(typeIdx);
                    importedFunctionCount++;
                }
                case 0x01 -> {
                    imports.add(new Import(module, name, new Import.Table(readTableType(sec))));
                    importedTableCount++;
                }
                case 0x02 -> {
                    imports.add(new Import(module, name,
                            new Import.Memory(readLimits(sec, MAX_MEMORY_PAGES, "memory (pages)"))));
                    importedMemoryCount++;
                }
                case 0x03 -> {
                    imports.add(new Import(module, name, new Import.GlobalImport(readGlobalType(sec))));
                    importedGlobalCount++;
                }
                default -> throw new WasmParseException("invalid import kind 0x" + Integer.toHexString(kind)
                        + " at offset " + (sec.position() - 1));
            }
        }
    }

    private void parseFunctions(WasmReader sec) {
        int n = sec.readVecCount();
        for (int i = 0; i < n; i++) {
            int typeIdx = sec.readU32();
            checkTypeIndex(typeIdx, sec.position());
            functionTypeIndices.add(typeIdx);
        }
    }

    private void parseTables(WasmReader sec) {
        int n = sec.readVecCount();
        for (int i = 0; i < n; i++) {
            tables.add(readTableType(sec));
        }
    }

    private void parseMemories(WasmReader sec) {
        int n = sec.readVecCount();
        for (int i = 0; i < n; i++) {
            memories.add(readLimits(sec, MAX_MEMORY_PAGES, "memory (pages)"));
        }
    }

    private void parseGlobals(WasmReader sec) {
        int n = sec.readVecCount();
        for (int i = 0; i < n; i++) {
            GlobalType type = readGlobalType(sec);
            ConstExpr init = readConstExpr(sec);
            globals.add(new Global(type, init));
        }
    }

    private void parseExports(WasmReader sec) {
        int n = sec.readVecCount();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < n; i++) {
            String name = sec.readName();
            if (!names.add(name)) {
                throw new WasmParseException("duplicate export name \"" + name + "\"");
            }
            ExternalKind kind = ExternalKind.fromByte(sec.readByte(), sec.position() - 1);
            int idx = sec.readU32();
            int limit = switch (kind) {
                case FUNCTION -> functionCount();
                case TABLE -> tableCount();
                case MEMORY -> memoryCount();
                case GLOBAL -> globalCount();
            };
            if (Integer.toUnsignedLong(idx) >= limit) {
                throw new WasmParseException("export \"" + name + "\" " + kind + " index " + idx
                        + " out of range (" + limit + ")");
            }
            exports.add(new Export(name, kind, idx));
        }
    }

    private void parseStart(WasmReader sec) {
        int idx = sec.readU32();
        if (Integer.toUnsignedLong(idx) >= functionCount()) {
            throw new WasmParseException("start function index " + idx + " out of range");
        }
        startFunction = idx;
    }

    private void parseElements(WasmReader sec) {
        int n = sec.readVecCount();
        for (int i = 0; i < n; i++) {
            elements.add(readElement(sec));
        }
    }

    private ElementSegment readElement(WasmReader sec) {
        int flags = sec.readU32();
        return switch (flags) {
            case 0 -> {
                ConstExpr offset = readConstExpr(sec);
                yield new ElementSegment(ElementSegment.Mode.ACTIVE, 0, offset, ValType.FUNCREF,
                        readFuncIdxVec(sec), null);
            }
            case 1 -> {
                readElemKind(sec);
                yield new ElementSegment(ElementSegment.Mode.PASSIVE, 0, null, ValType.FUNCREF,
                        readFuncIdxVec(sec), null);
            }
            case 2 -> {
                int tableIdx = sec.readU32();
                checkTableIndex(tableIdx, sec.position());
                ConstExpr offset = readConstExpr(sec);
                readElemKind(sec);
                yield new ElementSegment(ElementSegment.Mode.ACTIVE, tableIdx, offset, ValType.FUNCREF,
                        readFuncIdxVec(sec), null);
            }
            case 3 -> {
                readElemKind(sec);
                yield new ElementSegment(ElementSegment.Mode.DECLARATIVE, 0, null, ValType.FUNCREF,
                        readFuncIdxVec(sec), null);
            }
            case 4 -> {
                ConstExpr offset = readConstExpr(sec);
                yield new ElementSegment(ElementSegment.Mode.ACTIVE, 0, offset, ValType.FUNCREF,
                        null, readExprVec(sec));
            }
            case 5 -> {
                ValType t = readRefType(sec);
                yield new ElementSegment(ElementSegment.Mode.PASSIVE, 0, null, t, null, readExprVec(sec));
            }
            case 6 -> {
                int tableIdx = sec.readU32();
                checkTableIndex(tableIdx, sec.position());
                ConstExpr offset = readConstExpr(sec);
                ValType t = readRefType(sec);
                yield new ElementSegment(ElementSegment.Mode.ACTIVE, tableIdx, offset, t, null,
                        readExprVec(sec));
            }
            case 7 -> {
                ValType t = readRefType(sec);
                yield new ElementSegment(ElementSegment.Mode.DECLARATIVE, 0, null, t, null,
                        readExprVec(sec));
            }
            default -> throw new WasmParseException("invalid element segment flags " + flags);
        };
    }

    private int[] readFuncIdxVec(WasmReader sec) {
        int n = sec.readVecCount();
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int f = sec.readU32();
            if (Integer.toUnsignedLong(f) >= functionCount()) {
                throw new WasmParseException("element function index " + f + " out of range");
            }
            out[i] = f;
        }
        return out;
    }

    private List<ConstExpr> readExprVec(WasmReader sec) {
        int n = sec.readVecCount();
        List<ConstExpr> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(readConstExpr(sec));
        }
        return out;
    }

    private void readElemKind(WasmReader sec) {
        int b = sec.readByte();
        if (b != 0x00) {
            throw new WasmParseException("invalid elemkind 0x" + Integer.toHexString(b)
                    + " at offset " + (sec.position() - 1));
        }
    }

    private ValType readRefType(WasmReader sec) {
        int offset = sec.position();
        ValType t = ValType.fromByte(sec.readByte(), offset);
        if (!t.isReference()) {
            throw new WasmParseException("expected a reference type at offset " + offset);
        }
        return t;
    }

    private void parseCode(WasmReader sec) {
        codeSectionSeen = true;
        int n = sec.readVecCount();
        int expected = functionCount() - importedFunctionCount;
        if (n != expected) {
            throw new WasmParseException("code section has " + n + " entries but "
                    + expected + " functions were declared");
        }
        for (int i = 0; i < n; i++) {
            int bodySize = sec.readU32();
            if (Integer.toUnsignedLong(bodySize) > sec.remaining()) {
                throw new WasmParseException("function body size " + Integer.toUnsignedLong(bodySize)
                        + " exceeds remaining input");
            }
            WasmReader bodyReader = sec.subReader(bodySize);

            ValType[] locals = readLocals(bodyReader);
            byte[] body = bodyReader.readBytes(bodyReader.remaining());

            int funcIndex = importedFunctionCount + i;
            FuncType funcType = types.get(functionTypeIndices.get(funcIndex));
            int localCount = funcType.params().size() + locals.length;

            SideTable sideTable = new CodeScanner(types, functionCount(), globalCount(),
                    tableCount(), memoryCount(), elements.size(), dataCount, funcType, localCount, body)
                    .scan();

            code.add(new FunctionCode(functionTypeIndices.get(funcIndex), locals, body, sideTable));
        }
    }

    private ValType[] readLocals(WasmReader bodyReader) {
        int decls = bodyReader.readVecCount();
        long total = 0;
        List<ValType> expanded = new ArrayList<>();
        for (int d = 0; d < decls; d++) {
            long count = Integer.toUnsignedLong(bodyReader.readU32());
            ValType t = ValType.fromByte(bodyReader.readByte(), bodyReader.position() - 1);
            total += count;
            if (total > MAX_LOCALS) {
                throw new WasmParseException("too many locals (" + total + " > " + MAX_LOCALS + ")");
            }
            for (long k = 0; k < count; k++) {
                expanded.add(t);
            }
        }
        return expanded.toArray(new ValType[0]);
    }

    private void parseData(WasmReader sec) {
        int n = sec.readVecCount();
        if (dataCount >= 0 && n != dataCount) {
            throw new WasmParseException("data section has " + n + " segments but DataCount declared "
                    + dataCount);
        }
        for (int i = 0; i < n; i++) {
            int flags = sec.readU32();
            switch (flags) {
                case 0 -> {
                    requireMemoryForActiveData();
                    ConstExpr offset = readConstExpr(sec);
                    byte[] bytes = readByteVec(sec);
                    datas.add(new DataSegment(DataSegment.Mode.ACTIVE, 0, offset, bytes));
                }
                case 1 -> {
                    byte[] bytes = readByteVec(sec);
                    datas.add(new DataSegment(DataSegment.Mode.PASSIVE, 0, null, bytes));
                }
                case 2 -> {
                    int memIdx = sec.readU32();
                    if (memIdx != 0) {
                        throw new WasmParseException("unsupported feature multi-memory (data memory index "
                                + memIdx + ")");
                    }
                    requireMemoryForActiveData();
                    ConstExpr offset = readConstExpr(sec);
                    byte[] bytes = readByteVec(sec);
                    datas.add(new DataSegment(DataSegment.Mode.ACTIVE, 0, offset, bytes));
                }
                default -> throw new WasmParseException("invalid data segment flags " + flags);
            }
        }
    }

    private void requireMemoryForActiveData() {
        if (memoryCount() == 0) {
            throw new WasmParseException("active data segment but the module has no memory");
        }
    }

    private byte[] readByteVec(WasmReader sec) {
        int len = sec.readU32();
        if (Integer.toUnsignedLong(len) > sec.remaining()) {
            throw new WasmParseException("data segment length " + Integer.toUnsignedLong(len)
                    + " exceeds remaining input");
        }
        return sec.readBytes(len);
    }

    private void parseDataCount(WasmReader sec) {
        dataCount = sec.readU32();
        if (dataCount < 0) {
            throw new WasmParseException("DataCount value too large");
        }
    }

    // --- shared readers ---

    private TableType readTableType(WasmReader sec) {
        int offset = sec.position();
        ValType elem = ValType.fromByte(sec.readByte(), offset);
        if (!elem.isReference()) {
            throw new WasmParseException("table element type must be a reference type at offset " + offset);
        }
        return new TableType(elem, readLimits(sec, MAX_TABLE_ENTRIES, "table (entries)"));
    }

    private Limits readLimits(WasmReader sec, long cap, String kind) {
        int flag = sec.readByte();
        return switch (flag) {
            case 0x00 -> {
                long min = Integer.toUnsignedLong(sec.readU32());
                checkLimit(min, cap, kind, "minimum");
                yield new Limits(min, -1);
            }
            case 0x01 -> {
                long min = Integer.toUnsignedLong(sec.readU32());
                long max = Integer.toUnsignedLong(sec.readU32());
                checkLimit(min, cap, kind, "minimum");
                checkLimit(max, cap, kind, "maximum");
                if (max < min) {
                    throw new WasmParseException("limits maximum " + max + " below minimum " + min);
                }
                yield new Limits(min, max);
            }
            case 0x02, 0x03 -> throw new WasmParseException(
                    "unsupported feature atomics/threads (shared memory limits flag 0x"
                            + Integer.toHexString(flag) + ")");
            default -> throw new WasmParseException("invalid limits flag 0x" + Integer.toHexString(flag)
                    + " at offset " + (sec.position() - 1));
        };
    }

    private static void checkLimit(long value, long cap, String kind, String which) {
        if (value > cap) {
            throw new WasmParseException(kind + " " + which + " " + value
                    + " exceeds the supported maximum of " + cap);
        }
    }

    private GlobalType readGlobalType(WasmReader sec) {
        ValType type = ValType.fromByte(sec.readByte(), sec.position() - 1);
        int mut = sec.readByte();
        boolean mutable = switch (mut) {
            case 0x00 -> false;
            case 0x01 -> true;
            default -> throw new WasmParseException("invalid mutability 0x" + Integer.toHexString(mut)
                    + " at offset " + (sec.position() - 1));
        };
        return new GlobalType(type, mutable);
    }

    /**
     * Parses a constant expression (global init or active offset): a sequence of
     * constant instructions terminated by {@code end}. Captures the raw instruction
     * bytes excluding the terminating {@code end}. Validates each opcode and its
     * index against the relevant space.
     */
    private ConstExpr readConstExpr(WasmReader sec) {
        int start = sec.position();
        while (true) {
            int opPc = sec.position();
            int op = sec.readByte();
            switch (op) {
                case 0x41 -> sec.readS32();     // i32.const
                case 0x42 -> sec.readS64();     // i64.const
                case 0x43 -> sec.readF32Bits(); // f32.const
                case 0x44 -> sec.readF64Bits(); // f64.const
                case 0x23 -> {                  // global.get
                    int idx = sec.readU32();
                    // Constant expressions may only reference imported globals.
                    if (Integer.toUnsignedLong(idx) >= importedGlobalCount) {
                        throw new WasmParseException("constant expression global.get " + idx
                                + " must reference an imported global at offset " + opPc);
                    }
                }
                case 0xD0 -> ValType.fromByte(sec.readByte(), sec.position() - 1); // ref.null t
                case 0xD2 -> {                  // ref.func
                    int idx = sec.readU32();
                    if (Integer.toUnsignedLong(idx) >= functionCount()) {
                        throw new WasmParseException("constant expression ref.func " + idx
                                + " out of range at offset " + opPc);
                    }
                }
                case 0x0B -> {                  // end
                    return new ConstExpr(sec.slice(start, opPc));
                }
                default -> throw new WasmParseException("invalid constant expression opcode 0x"
                        + Integer.toHexString(op) + " at offset " + opPc);
            }
        }
    }

    private void checkTypeIndex(int idx, int offset) {
        if (Integer.toUnsignedLong(idx) >= types.size()) {
            throw new WasmParseException("type index " + idx + " out of range at offset " + offset);
        }
    }

    private void checkTableIndex(int idx, int offset) {
        if (Integer.toUnsignedLong(idx) >= tableCount()) {
            throw new WasmParseException("table index " + idx + " out of range at offset " + offset);
        }
    }

    private void finalValidation() {
        if (memoryCount() > 1) {
            throw new WasmParseException("unsupported feature multi-memory ("
                    + memoryCount() + " memories declared)");
        }
        int definedFunctions = functionCount() - importedFunctionCount;
        if (definedFunctions != code.size()) {
            throw new WasmParseException("declared " + definedFunctions
                    + " functions but the code section has " + code.size() + " bodies"
                    + (codeSectionSeen ? "" : " (code section missing)"));
        }
        if (dataCount >= 0 && dataCount != datas.size()) {
            throw new WasmParseException("DataCount declared " + dataCount
                    + " but the data section has " + datas.size() + " segments");
        }
    }
}
