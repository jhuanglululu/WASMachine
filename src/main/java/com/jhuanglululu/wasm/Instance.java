package com.jhuanglululu.wasm;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A ready-to-run instantiation of a {@link Module}: it resolves the module's
 * (function) imports against host bindings, then {@link #instantiate()} produces a
 * fresh {@link ExecutionContext} with its own memory, globals and tables, active
 * segments applied and the start function (if any) run.
 *
 * <p>The {@code Instance} holds only the shared, immutable configuration (the module,
 * the resolved host functions, the export table). All mutable execution state lives in
 * the {@link ExecutionContext}, which is what a fork clones — so one {@code Instance}
 * can back many independent contexts.
 *
 * <p>Only <b>function</b> imports are supported (as {@code module.name -> HostFunction});
 * this matches the {@code wasm32-unknown-unknown} target, which defines and exports its
 * own memory/globals rather than importing them. An imported table, memory, or global
 * is an instantiation error.
 */
public final class Instance {

    /** Thrown when a module cannot be instantiated (missing import, OOB active segment). */
    public static final class WasmInstantiationException extends RuntimeException {
        public WasmInstantiationException(String message) {
            super(message);
        }
    }

    private final Module module;
    private final HostFunction[] hostFunctions; // indexed by imported-function index
    private final Map<String, Export> exportsByName;

    /**
     * @param module  the parsed module
     * @param imports host bindings keyed {@code "module.name"} for every function import
     * @throws WasmInstantiationException if an import is missing or is a non-function import
     */
    public Instance(Module module, Map<String, HostFunction> imports) {
        this.module = module;
        this.hostFunctions = new HostFunction[module.importedFunctionCount()];
        int funcIdx = 0;
        for (Import imp : module.imports()) {
            String key = imp.module() + "." + imp.name();
            switch (imp.descriptor()) {
                case Import.Func ignored -> {
                    HostFunction hf = imports.get(key);
                    if (hf == null) {
                        throw new WasmInstantiationException("missing host import: " + key);
                    }
                    hostFunctions[funcIdx++] = hf;
                }
                case Import.Table ignored ->
                        throw new WasmInstantiationException("unsupported imported table: " + key);
                case Import.Memory ignored ->
                        throw new WasmInstantiationException("unsupported imported memory: " + key);
                case Import.GlobalImport ignored ->
                        throw new WasmInstantiationException("unsupported imported global: " + key);
            }
        }
        Map<String, Export> map = new HashMap<>();
        for (Export e : module.exports()) {
            map.put(e.name(), e);
        }
        this.exportsByName = map;
    }

    Module module() {
        return module;
    }

    HostFunction hostFunction(int importedFunctionIndex) {
        return hostFunctions[importedFunctionIndex];
    }

    byte[] dataSegment(int index) {
        return module.datas().get(index).rawData();
    }

    /** The resolved function-index contents of an element segment (funcref form). */
    int[] elementSegment(int index) {
        return resolveElementItems(module.elements().get(index));
    }

    /**
     * Builds a fresh execution context: linear memory sized from limits, globals
     * evaluated from their init expressions, tables built and active element segments
     * written, active data segments copied (bounds-checked), passive segments retained,
     * and the start function run to completion if present.
     */
    public ExecutionContext instantiate() {
        ExecutionContext ctx = new ExecutionContext(this);

        // Memory (at most one; imported memory is rejected in the constructor).
        if (!module.memories().isEmpty()) {
            Limits limits = module.memories().get(0);
            int min = (int) limits.min();
            int max = resolveMaxPages(limits);
            ctx.initMemory(min, max);
        }

        // Globals.
        List<Global> globals = module.globals();
        ctx.initGlobals(globals.size());
        for (int i = 0; i < globals.size(); i++) {
            ctx.setGlobal(i, evalConstExpr(globals.get(i).init().bytes(), ctx));
        }

        // Tables.
        List<TableType> tableTypes = module.tables();
        int[][] tables = new int[tableTypes.size()][];
        int[] tableMax = new int[tableTypes.size()];
        for (int i = 0; i < tableTypes.size(); i++) {
            Limits limits = tableTypes.get(i).limits();
            int[] t = new int[(int) limits.min()];
            java.util.Arrays.fill(t, -1); // null references
            tables[i] = t;
            // Cap the growth ceiling even when the table declares no maximum, so a huge
            // table.grow delta returns -1 rather than attempting a multi-GiB allocation.
            tableMax[i] = limits.hasMax()
                    ? (int) Math.min(limits.max(), ModuleParser.MAX_TABLE_ENTRIES)
                    : ModuleParser.MAX_TABLE_ENTRIES;
        }
        ctx.initTables(tables, tableMax);

        ctx.initDropFlags(module.datas().size(), module.elements().size());

        // Active/declarative element segments.
        List<ElementSegment> elements = module.elements();
        for (int i = 0; i < elements.size(); i++) {
            ElementSegment e = elements.get(i);
            if (e.mode() == ElementSegment.Mode.ACTIVE) {
                int[] items = resolveElementItems(e);
                int offset = (int) evalConstExpr(e.offset().bytes(), ctx);
                int[] table = tables[e.tableIndex()];
                if (offset < 0 || (long) offset + items.length > table.length) {
                    throw new WasmInstantiationException("active element segment out of table bounds");
                }
                System.arraycopy(items, 0, table, offset, items.length);
                ctx.markElemDropped(i);
            } else if (e.mode() == ElementSegment.Mode.DECLARATIVE) {
                ctx.markElemDropped(i);
            }
        }

        // Active data segments.
        List<DataSegment> datas = module.datas();
        for (int i = 0; i < datas.size(); i++) {
            DataSegment d = datas.get(i);
            if (d.mode() == DataSegment.Mode.ACTIVE) {
                byte[] bytes = d.rawData();
                int offset = (int) evalConstExpr(d.offset().bytes(), ctx);
                if (offset < 0 || (long) offset + bytes.length > ctx.memorySize()) {
                    throw new WasmInstantiationException("active data segment out of memory bounds");
                }
                for (int k = 0; k < bytes.length; k++) {
                    ctx.storeByte(offset + k, bytes[k]);
                }
                ctx.markDataDropped(i);
            }
        }

        // Start function.
        module.startFunction().ifPresent(start -> {
            ctx.setupEntryFrame(start, new long[0]);
            ExecResult r = ctx.run(Long.MAX_VALUE);
            if (!(r instanceof ExecResult.Completed)) {
                throw new WasmInstantiationException("start function did not complete: " + r);
            }
        });

        return ctx;
    }

    private static int resolveMaxPages(Limits limits) {
        // Cap so the linear memory fits a Java array (< 2^31 bytes).
        long cap = Integer.MAX_VALUE / ExecutionContext.PAGE;
        long max = limits.hasMax() ? limits.max() : 65536;
        return (int) Math.min(max, cap);
    }

    private int[] resolveElementItems(ElementSegment e) {
        if (e.isFunctionIndexForm()) {
            return e.functionIndices();
        }
        List<ConstExpr> exprs = e.initExpressions();
        int[] out = new int[exprs.size()];
        for (int i = 0; i < exprs.size(); i++) {
            out[i] = (int) evalConstExpr(exprs.get(i).bytes(), null);
        }
        return out;
    }

    /**
     * Invokes an exported function by name.
     *
     * @param ctx  a context from {@link #instantiate()} that is idle (no active frames)
     * @param name the export name
     * @param args argument slots (raw 64-bit), one per parameter
     * @param fuel instruction budget for this step
     */
    public ExecResult invoke(ExecutionContext ctx, String name, long[] args, long fuel) {
        Export e = exportsByName.get(name);
        if (e == null || e.kind() != ExternalKind.FUNCTION) {
            throw new IllegalArgumentException("no exported function named \"" + name + "\"");
        }
        int fi = e.index();
        if (fi < module.importedFunctionCount()) {
            throw new IllegalArgumentException("exported function \"" + name + "\" is an import");
        }
        FuncType type = module.functionType(fi);
        if (args.length != type.params().size()) {
            throw new IllegalArgumentException("expected " + type.params().size()
                    + " argument(s) but got " + args.length);
        }
        if (ctx.frameCount() != 0) {
            throw new IllegalStateException("context is not idle");
        }
        ctx.setupEntryFrame(fi, args);
        return ctx.run(fuel);
    }

    /** Resume a suspended or fuel-exhausted context, supplying no host result value. */
    public ExecResult resume(ExecutionContext ctx, long fuel) {
        return resume(ctx, fuel, 0L);
    }

    /**
     * Resume a suspended or fuel-exhausted context. If the context is suspended in a
     * host call that has a result, {@code hostResult} is pushed as that call's return
     * value; otherwise it is ignored.
     */
    public ExecResult resume(ExecutionContext ctx, long fuel, long hostResult) {
        if (ctx.isSuspended()) {
            if (ctx.suspendHostArity() == 1) {
                ctx.pushResult(hostResult);
            }
            ctx.clearSuspension();
        } else if (ctx.status() != ExecutionContext.Status.RUNNABLE || ctx.frameCount() == 0) {
            throw new IllegalStateException("context is not resumable (status " + ctx.status() + ")");
        }
        return ctx.run(fuel);
    }

    /**
     * Evaluates a constant expression (global init / active offset / element item).
     * Supports {@code i32/i64/f32/f64.const}, {@code global.get} (of an already-evaluated
     * global), {@code ref.func} and {@code ref.null}. The bytes exclude the terminating
     * {@code end}.
     */
    private long evalConstExpr(byte[] bytes, ExecutionContext ctx) {
        long value = 0;
        int p = 0;
        while (p < bytes.length) {
            int op = bytes[p++] & 0xFF;
            switch (op) {
                case 0x41 -> { // i32.const
                    int[] r = readS32(bytes, p);
                    value = r[0];
                    p = r[1];
                }
                case 0x42 -> { // i64.const
                    long[] r = readS64(bytes, p);
                    value = r[0];
                    p = (int) r[1];
                }
                case 0x43 -> { // f32.const
                    value = (bytes[p] & 0xFF) | ((bytes[p + 1] & 0xFF) << 8)
                            | ((bytes[p + 2] & 0xFF) << 16) | ((bytes[p + 3] & 0xFF) << 24);
                    p += 4;
                }
                case 0x44 -> { // f64.const
                    long v = 0;
                    for (int i = 0; i < 8; i++) {
                        v |= ((long) (bytes[p + i] & 0xFF)) << (8 * i);
                    }
                    value = v;
                    p += 8;
                }
                case 0x23 -> { // global.get
                    int[] r = readU32(bytes, p);
                    value = ctx == null ? 0 : ctx.getGlobal(r[0]);
                    p = r[1];
                }
                case 0xD0 -> { // ref.null
                    p++; // heap type byte
                    value = -1;
                }
                case 0xD2 -> { // ref.func
                    int[] r = readU32(bytes, p);
                    value = r[0];
                    p = r[1];
                }
                default -> throw new WasmInstantiationException(
                        "unsupported constant expression opcode 0x" + Integer.toHexString(op));
            }
        }
        return value;
    }

    private static int[] readU32(byte[] b, int p) {
        int result = 0;
        int shift = 0;
        int by;
        do {
            by = b[p++];
            result |= (by & 0x7F) << shift;
            shift += 7;
        } while ((by & 0x80) != 0);
        return new int[] {result, p};
    }

    private static int[] readS32(byte[] b, int p) {
        int result = 0;
        int shift = 0;
        int by;
        do {
            by = b[p++];
            result |= (by & 0x7F) << shift;
            shift += 7;
        } while ((by & 0x80) != 0);
        if (shift < 32 && (by & 0x40) != 0) {
            result |= -(1 << shift);
        }
        return new int[] {result, p};
    }

    private static long[] readS64(byte[] b, int p) {
        long result = 0;
        int shift = 0;
        int by;
        do {
            by = b[p++];
            result |= ((long) (by & 0x7F)) << shift;
            shift += 7;
        } while ((by & 0x80) != 0);
        if (shift < 64 && (by & 0x40) != 0) {
            result |= -(1L << shift);
        }
        return new long[] {result, p};
    }
}
