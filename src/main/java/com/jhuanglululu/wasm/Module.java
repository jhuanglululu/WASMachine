package com.jhuanglululu.wasm;

import java.util.List;
import java.util.OptionalInt;

/**
 * A parsed WebAssembly module: the immutable result of {@link #parse(byte[])}.
 *
 * <p>This is a general, minimal-WASM library with no dependency on and no knowledge
 * of any host application. It accepts exactly what stable {@code rustc} emits for the
 * {@code wasm32-unknown-unknown} target (MVP core + sign-extension + non-trapping
 * float→int + bulk-memory + the reference-types/multi-value <em>encodings</em>
 * rustc uses) and rejects, with a feature-naming {@link WasmParseException}, anything
 * outside that set: SIMD, atomics, exception handling, tail calls, multi-memory, GC.
 *
 * <h2>Validation depth</h2>
 * Parsing performs structural validation, feature gating, and index-space bounds
 * checks (every function/type/global/local/table/data/element index and branch label
 * is checked against its space). It deliberately does <b>not</b> perform full operand
 * type-checking of function bodies: a type-incorrect body still parses, and the
 * interpreter (added later) traps at runtime. Malformed input — bad magic, truncated
 * or overrun sections, overlong LEB128 — always throws {@link WasmParseException};
 * the parser never hangs, never throws a raw {@link ArrayIndexOutOfBoundsException},
 * and bounds every allocation by the actual input size.
 *
 * <h2>Function bodies</h2>
 * Each defined function's body is kept as raw bytes for later execution, but a
 * {@link SideTable} is precomputed at parse time so the interpreter never scans the
 * bytecode forward at runtime (see {@link SideTable}).
 */
public final class Module {

    private final List<FuncType> types;
    private final List<Import> imports;
    private final int importedFunctionCount;
    private final int[] functionTypeIndices;
    private final List<TableType> tables;
    private final List<Limits> memories;
    private final List<Global> globals;
    private final List<Export> exports;
    private final List<ElementSegment> elements;
    private final List<DataSegment> datas;
    private final List<FunctionCode> code;
    private final int startFunction; // -1 if absent
    private final int dataCount;     // -1 if no DataCount section

    Module(List<FuncType> types, List<Import> imports, int importedFunctionCount,
            int[] functionTypeIndices, List<TableType> tables, List<Limits> memories,
            List<Global> globals, List<Export> exports, List<ElementSegment> elements,
            List<DataSegment> datas, List<FunctionCode> code, int startFunction, int dataCount) {
        this.types = List.copyOf(types);
        this.imports = List.copyOf(imports);
        this.importedFunctionCount = importedFunctionCount;
        this.functionTypeIndices = functionTypeIndices.clone();
        this.tables = List.copyOf(tables);
        this.memories = List.copyOf(memories);
        this.globals = List.copyOf(globals);
        this.exports = List.copyOf(exports);
        this.elements = List.copyOf(elements);
        this.datas = List.copyOf(datas);
        this.code = List.copyOf(code);
        this.startFunction = startFunction;
        this.dataCount = dataCount;
    }

    /**
     * Parses a WebAssembly binary module.
     *
     * @param bytes the complete module bytes
     * @return the parsed module
     * @throws WasmParseException if the input is malformed or uses an unsupported feature
     */
    public static Module parse(byte[] bytes) {
        if (bytes == null) {
            throw new WasmParseException("input is null");
        }
        return new ModuleParser(bytes).parse();
    }

    /** The type (function-signature) section. */
    public List<FuncType> types() {
        return types;
    }

    /** All imports, in declaration order. */
    public List<Import> imports() {
        return imports;
    }

    /** The number of imported functions (they occupy the low function indices). */
    public int importedFunctionCount() {
        return importedFunctionCount;
    }

    /**
     * The full function index space size (imported + defined). Function indices
     * {@code [0, importedFunctionCount)} are imports; the rest are defined functions
     * whose bodies are in {@link #code()}.
     */
    public int functionCount() {
        return functionTypeIndices.length;
    }

    /**
     * The signature of the function at {@code index} in the whole function index space.
     *
     * @throws IndexOutOfBoundsException if {@code index} is not a valid function index
     */
    public FuncType functionType(int index) {
        return types.get(functionTypeIndices[index]);
    }

    /** Table declarations (defined tables; imported tables appear in {@link #imports()}). */
    public List<TableType> tables() {
        return tables;
    }

    /** Memory limits (defined memories; imported memories appear in {@link #imports()}). */
    public List<Limits> memories() {
        return memories;
    }

    /** Defined globals (imported globals appear in {@link #imports()}). */
    public List<Global> globals() {
        return globals;
    }

    /** All exports. */
    public List<Export> exports() {
        return exports;
    }

    /** Element segments (active, passive, and declarative). */
    public List<ElementSegment> elements() {
        return elements;
    }

    /** Data segments (active and passive). */
    public List<DataSegment> datas() {
        return datas;
    }

    /** Per-defined-function code (locals, body bytes, sidetable), in function order. */
    public List<FunctionCode> code() {
        return code;
    }

    /** The start function index, if a start section is present. */
    public OptionalInt startFunction() {
        return startFunction < 0 ? OptionalInt.empty() : OptionalInt.of(startFunction);
    }

    /** The declared data-segment count from the DataCount section, or -1 if absent. */
    public int dataCount() {
        return dataCount;
    }
}
