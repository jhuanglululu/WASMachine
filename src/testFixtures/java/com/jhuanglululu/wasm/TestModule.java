package com.jhuanglululu.wasm;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.jhuanglululu.wasm.WasmBuilder.Buf;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Test helper that assembles a runnable single-("main")-function module with optional
 * imports, memory, globals, tables, elements and data. Section framing and LEB
 * encoding are hand-rolled via {@link WasmBuilder}, independent of the parser.
 */
final class TestModule {

    // Value type bytes.
    static final int I32 = 0x7F;
    static final int I64 = 0x7E;
    static final int F32 = 0x7D;
    static final int F64 = 0x7C;

    private Buf types = new Buf().vec(1).raw(0x60, 0x00, 0x00); // default: () -> ()
    private int mainTypeIndex;
    private Buf locals = new Buf().vec(0);
    private Buf instr = new Buf().raw(0x0B); // default: just `end`

    private final List<String> importModules = new ArrayList<>();
    private final List<String> importNames = new ArrayList<>();
    private final List<Integer> importTypeIndices = new ArrayList<>();

    private Buf memory;
    private Buf globals;
    private Buf tables;
    private Buf elements;
    private Buf dataCount;
    private Buf data;

    TestModule types(Buf t) {
        this.types = t;
        return this;
    }

    TestModule mainType(int index) {
        this.mainTypeIndex = index;
        return this;
    }

    TestModule locals(Buf l) {
        this.locals = l;
        return this;
    }

    TestModule body(Buf instructions) {
        this.instr = new Buf().buf(instructions).raw(0x0B); // append `end`
        return this;
    }

    /** Body bytes verbatim (must include the terminating `end`). */
    TestModule rawBody(Buf instructions) {
        this.instr = instructions;
        return this;
    }

    TestModule importFunc(String module, String name, int typeIndex) {
        importModules.add(module);
        importNames.add(name);
        importTypeIndices.add(typeIndex);
        return this;
    }

    TestModule memory(int minPages) {
        this.memory = new Buf().vec(1).raw(0x00).uleb(minPages);
        return this;
    }

    TestModule memory(int minPages, int maxPages) {
        this.memory = new Buf().vec(1).raw(0x01).uleb(minPages).uleb(maxPages);
        return this;
    }

    TestModule globals(Buf g) {
        this.globals = g;
        return this;
    }

    TestModule tables(Buf t) {
        this.tables = t;
        return this;
    }

    TestModule elements(Buf e) {
        this.elements = e;
        return this;
    }

    TestModule dataCount(int n) {
        this.dataCount = new Buf().uleb(n);
        return this;
    }

    TestModule data(Buf d) {
        this.data = d;
        return this;
    }

    /** The index of the exported "main" function in the whole function index space. */
    int mainFunctionIndex() {
        return importModules.size();
    }

    byte[] build() {
        WasmBuilder b = new WasmBuilder();
        b.section(1, types);
        if (!importModules.isEmpty()) {
            Buf imp = new Buf().vec(importModules.size());
            for (int i = 0; i < importModules.size(); i++) {
                imp.name(importModules.get(i)).name(importNames.get(i)).raw(0x00).uleb(importTypeIndices.get(i));
            }
            b.section(2, imp);
        }
        b.section(3, new Buf().vec(1).uleb(mainTypeIndex));
        if (tables != null) {
            b.section(4, tables);
        }
        if (memory != null) {
            b.section(5, memory);
        }
        if (globals != null) {
            b.section(6, globals);
        }
        b.section(7, new Buf().vec(1).name("main").raw(0x00).uleb(mainFunctionIndex()));
        if (elements != null) {
            b.section(9, elements);
        }
        if (dataCount != null) {
            b.section(12, dataCount);
        }
        Buf codeBody = new Buf().buf(locals).buf(instr);
        b.section(10, new Buf().vec(1).uleb(codeBody.toBytes().length).buf(codeBody));
        if (data != null) {
            b.section(11, data);
        }
        return b.build();
    }

    Instance instantiate(Map<String, HostFunction> imports) {
        return new Instance(Module.parse(build()), imports);
    }

    // --- constant-instruction helpers ---

    static Buf i32Const(int v) {
        return new Buf().raw(0x41).bytes(WasmBuilder.sleb(v));
    }

    static Buf i64Const(long v) {
        return new Buf().raw(0x42).bytes(WasmBuilder.sleb(v));
    }

    static Buf f32Const(float v) {
        return new Buf().raw(0x43).f32(v);
    }

    static Buf f64Const(double v) {
        return new Buf().raw(0x44).f64(v);
    }

    // --- one-shot runners for a () -> (resultType) function ---

    static ExecResult exec(int resultType, Buf instrs) {
        byte[] bytes = new TestModule()
                .types(new Buf().vec(1).raw(0x60, 0x00, 0x01, resultType))
                .body(instrs)
                .build();
        Instance inst = new Instance(Module.parse(bytes), Map.of());
        ExecutionContext ctx = inst.instantiate();
        return inst.invoke(ctx, "main", new long[0], 1_000_000);
    }

    static long[] completed(ExecResult r) {
        assertInstanceOf(ExecResult.Completed.class, r,
                () -> "expected Completed but was " + r);
        return ((ExecResult.Completed) r).values();
    }

    static int i32(Buf instrs) {
        return (int) completed(exec(I32, instrs))[0];
    }

    static long i64(Buf instrs) {
        return completed(exec(I64, instrs))[0];
    }

    static float f32(Buf instrs) {
        return Float.intBitsToFloat((int) completed(exec(F32, instrs))[0]);
    }

    static double f64(Buf instrs) {
        return Double.longBitsToDouble(completed(exec(F64, instrs))[0]);
    }
}
