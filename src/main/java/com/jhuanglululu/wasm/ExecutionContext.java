package com.jhuanglululu.wasm;

import java.util.Arrays;
import java.util.Objects;

/**
 * A single suspendable WASM execution: all guest state lives here as plain Java data
 * so execution can be suspended by returning from {@link #run} and resumed by
 * re-entering it. Guest calls never recurse on the Java call stack — an explicit
 * {@link Frame} stack is used instead.
 *
 * <p>Owned state (deep-copied by {@link #copy()}, the primitive a fork builds on):
 * an operand stack ({@code long[]}, raw bits — floats via {@code Float/Double.*Bits}),
 * a frame stack, its own little-endian linear {@code memory}, its own {@code globals},
 * its own tables, and the passive-segment drop bookkeeping. The one exception is the
 * {@link SharedRegion}, which a copy takes by reference — that is the entire point of it.
 *
 * <h2>Memory routing</h2>
 * Every load, store and bulk op resolves its address through {@link #region}: below
 * {@link SharedRegion#SHARED_BASE} is this task's private memory, at or above it the
 * instance's shared region. One compare, and a range can never span the two, because the
 * private array ends far below the base — a length that would cross simply runs off the end
 * of whichever region it started in and traps. {@code memory.grow}/{@code memory.size} are
 * private-only: the shared region grows solely through {@code SharedRegion.allocate}.
 *
 * <h2>Mid-host-call resume protocol</h2>
 * When a {@link HostFunction} calls {@link #suspend}, it throws {@link SuspendSignal};
 * the dispatch loop catches it <em>after</em> advancing the program counter past the
 * call and popping the call's arguments, records the suspended call's result arity,
 * and returns {@link ExecResult.Suspended}. On resume the interpreter pushes the
 * supplied host result (if the call has one) and continues exactly at the next
 * instruction — the host call appears to have returned that value.
 */
public final class ExecutionContext {

    /** Linear-memory page size in bytes. */
    static final int PAGE = 65536;

    /** Execution status of a context. */
    public enum Status {
        /** Ready to run or continue (fresh, or after fuel exhaustion). */
        RUNNABLE,
        /** Parked inside a host call, awaiting a supplied result. */
        SUSPENDED,
        /** The entry function returned. */
        COMPLETED,
        /** Execution trapped. */
        TRAPPED
    }

    /** One activation record. Guest calls push these instead of recursing in Java. */
    static final class Frame {
        int funcIndex;
        byte[] body;
        SideTable sideTable;
        long[] locals;
        int pc;
        int stackBase;
        int resultArity;
        // Label stack packed 4 ints per label: [base, branchTarget, keep, endPc].
        // isLoop is derived: a loop's branchTarget (its body start) differs from its
        // endPc, whereas a block/if branches to its own endPc.
        int[] labels;
        int labelSp;

        Frame copy() {
            Frame f = new Frame();
            f.funcIndex = funcIndex;
            f.body = body;             // immutable, shared
            f.sideTable = sideTable;   // immutable, shared
            f.locals = locals.clone();
            f.pc = pc;
            f.stackBase = stackBase;
            f.resultArity = resultArity;
            f.labels = labels.clone();
            f.labelSp = labelSp;
            return f;
        }
    }

    private final Instance instance;

    private long[] stack = new long[256];
    private int sp;

    private Frame[] frames = new Frame[16];
    private int frameSp;

    private byte[] memory = new byte[0];
    private int memPages;
    private int memMaxPages;

    // The instance's shared static region: shared by reference with every forked copy, so it
    // is the one piece of addressable state a fork does not duplicate.
    private SharedRegion shared = SharedRegion.NONE;
    // Where the last region() call landed inside the array it returned.
    private int regionOffset;

    private long[] globals = new long[0];

    private int[][] tables = new int[0][];
    private int[] tableMax = new int[0];

    private boolean[] dataDropped = new boolean[0];
    private boolean[] elemDropped = new boolean[0];

    private Status status = Status.RUNNABLE;
    private int suspendHostArity;
    private Object suspendRequest;
    private long[] completedValues;
    private long fuelConsumedThisRun;
    private long fuel; // remaining fuel for the current run(); a field so bulk ops can charge

    private static final int MAX_FRAMES = 1 << 16;

    ExecutionContext(Instance instance) {
        this.instance = instance;
    }

    // --- embedder / host API ---

    public Status status() {
        return status;
    }

    /** The <em>private</em> linear memory size in bytes (the shared region is not part of it). */
    public int memorySize() {
        return memPages * PAGE;
    }

    /** The number of linear-memory pages. */
    public int memoryPageCount() {
        return memPages;
    }

    /**
     * Attaches the instance's shared static region. Called once, on the context of task 0;
     * every forked context inherits the same region object through {@link #copy()}.
     */
    public void attachSharedRegion(SharedRegion region) {
        this.shared = Objects.requireNonNull(region);
    }

    public byte loadByte(int addr) {
        byte[] m = hostRegion(addr, 1);
        return m[regionOffset];
    }

    public void storeByte(int addr, byte value) {
        byte[] m = hostRegion(addr, 1);
        m[regionOffset] = value;
    }

    /** Reads a little-endian 32-bit value from guest memory (private or shared). */
    public int loadI32(int addr) {
        byte[] m = hostRegion(addr, 4);
        return readI32(m, regionOffset);
    }

    /** Writes a little-endian 32-bit value to guest memory (private or shared). */
    public void storeI32(int addr, int value) {
        byte[] m = hostRegion(addr, 4);
        writeI32(m, regionOffset, value);
    }

    /** Copies {@code length} bytes of guest memory starting at {@code addr}. */
    public byte[] readBytes(int addr, int length) {
        byte[] m = hostRegion(addr, length);
        int off = regionOffset;
        return Arrays.copyOfRange(m, off, off + length);
    }

    /** Writes {@code src} into guest memory at {@code addr} (bounds-checked, region-routed). */
    public void writeBytes(int addr, byte[] src) {
        byte[] m = hostRegion(addr, src.length);
        System.arraycopy(src, 0, m, regionOffset, src.length);
    }

    /**
     * The host-call form of {@link #region}: same routing (so a host write to a shared address
     * lands in the shared region), but a failure is a Java exception rather than a guest trap —
     * a host function that addresses out of bounds is a host bug, not a guest one.
     */
    private byte[] hostRegion(int addr, int length) {
        byte[] m = length < 0 ? null : region(addr & 0xFFFFFFFFL, length);
        if (m == null) {
            throw new IndexOutOfBoundsException("guest memory access at 0x"
                    + Integer.toHexString(addr) + " for " + length + " byte(s) is out of bounds");
        }
        return m;
    }

    public long readGlobal(int index) {
        return globals[index];
    }

    /**
     * Grows linear memory by {@code deltaPages} pages, honoring the configured
     * maximum (the {@code memory.grow} instruction semantics, callable from a host
     * function). Returns the previous page count, or {@code -1} if the growth would
     * exceed the maximum (memory unchanged).
     */
    public int growMemory(int deltaPages) {
        long np = (long) memPages + (deltaPages & 0xFFFFFFFFL);
        if (deltaPages < 0 || np > memMaxPages) {
            return -1;
        }
        int old = memPages;
        growMemoryUnchecked((int) np);
        return old;
    }

    /** Instructions executed during the most recent {@link #run} (for fuel accounting). */
    public long fuelConsumed() {
        return fuelConsumedThisRun;
    }

    /**
     * Suspends this context: a host function returns the result of this call to
     * abandon its Java frame and hand {@code request} to the embedder. Usage:
     * {@code throw ctx.suspend(request);}
     */
    public SuspendSignal suspend(Object request) {
        return new SuspendSignal(request);
    }

    // --- deep copy (fork primitive) ---

    /**
     * A fully independent deep copy: stacks, frames, memory, globals, tables. The shared static
     * region is the deliberate exception — it is taken by reference, so however many times a
     * task forks there is still exactly one of it.
     */
    public ExecutionContext copy() {
        ExecutionContext c = new ExecutionContext(instance);
        c.shared = shared;
        c.stack = stack.clone();
        c.sp = sp;
        c.frames = new Frame[frames.length];
        c.frameSp = frameSp;
        for (int i = 0; i < frameSp; i++) {
            c.frames[i] = frames[i].copy();
        }
        c.memory = memory.clone();
        c.memPages = memPages;
        c.memMaxPages = memMaxPages;
        c.globals = globals.clone();
        c.tables = new int[tables.length][];
        for (int i = 0; i < tables.length; i++) {
            c.tables[i] = tables[i].clone();
        }
        c.tableMax = tableMax.clone();
        c.dataDropped = dataDropped.clone();
        c.elemDropped = elemDropped.clone();
        c.status = status;
        c.suspendHostArity = suspendHostArity;
        c.suspendRequest = suspendRequest;
        c.completedValues = completedValues == null ? null : completedValues.clone();
        return c;
    }

    // --- instantiation-time setup (called by Instance) ---

    void initMemory(int pages, int maxPages) {
        this.memPages = pages;
        this.memMaxPages = maxPages;
        this.memory = new byte[pages * PAGE];
    }

    void growMemoryUnchecked(int newPages) {
        byte[] nm = new byte[newPages * PAGE];
        System.arraycopy(memory, 0, nm, 0, memory.length);
        memory = nm;
        memPages = newPages;
    }

    void initGlobals(int count) {
        this.globals = new long[count];
    }

    void setGlobal(int index, long value) {
        globals[index] = value;
    }

    long getGlobal(int index) {
        return globals[index];
    }

    void initTables(int[][] tables, int[] tableMax) {
        this.tables = tables;
        this.tableMax = tableMax;
    }

    void initDropFlags(int dataCount, int elemCount) {
        this.dataDropped = new boolean[dataCount];
        this.elemDropped = new boolean[elemCount];
    }

    void markDataDropped(int index) {
        dataDropped[index] = true;
    }

    void markElemDropped(int index) {
        elemDropped[index] = true;
    }

    byte[] memoryArray() {
        return memory;
    }

    int[] table(int index) {
        return tables[index];
    }

    // --- frame setup ---

    void setupEntryFrame(int funcIndex, long[] args) {
        Module module = instance.module();
        FuncType type = module.functionType(funcIndex);
        FunctionCode code = module.code().get(funcIndex - module.importedFunctionCount());
        Frame f = newFrame(funcIndex, type, code);
        System.arraycopy(args, 0, f.locals, 0, type.params().size());
        f.stackBase = sp;
        pushFunctionLabel(f);
        pushFrame(f);
    }

    private Frame newFrame(int funcIndex, FuncType type, FunctionCode code) {
        Frame f = new Frame();
        f.funcIndex = funcIndex;
        f.body = code.rawBody();
        f.sideTable = code.sideTable();
        f.resultArity = type.results().size();
        f.locals = new long[type.params().size() + code.rawLocals().length];
        f.pc = 0;
        f.labels = new int[64];
        f.labelSp = 0;
        return f;
    }

    private void pushFunctionLabel(Frame f) {
        // The implicit function-level label: branching to it (or `return`) leaves the
        // function. base = stackBase, keep = result arity. branchTarget == endPc so it
        // is not treated as a loop; reaching it (labelSp -> 0) means "return".
        pushLabel(f, f.stackBase, f.body.length, f.resultArity, f.body.length);
    }

    private void pushFrame(Frame f) {
        if (frameSp == frames.length) {
            frames = Arrays.copyOf(frames, frames.length * 2);
        }
        frames[frameSp++] = f;
    }

    private static void pushLabel(Frame f, int base, int branchTarget, int keep, int endPc) {
        int need = (f.labelSp + 1) * 4;
        if (need > f.labels.length) {
            f.labels = Arrays.copyOf(f.labels, f.labels.length * 2);
        }
        int b = f.labelSp * 4;
        f.labels[b] = base;
        f.labels[b + 1] = branchTarget;
        f.labels[b + 2] = keep;
        f.labels[b + 3] = endPc;
        f.labelSp++;
    }

    // --- resume support (called by Instance) ---

    boolean isSuspended() {
        return status == Status.SUSPENDED;
    }

    int suspendHostArity() {
        return suspendHostArity;
    }

    Object suspendRequest() {
        return suspendRequest;
    }

    int frameCount() {
        return frameSp;
    }

    void pushResult(long value) {
        push(value);
    }

    void clearSuspension() {
        status = Status.RUNNABLE;
        suspendRequest = null;
    }

    // --- operand stack primitives ---

    /**
     * Sanity ceiling for the operand stack, enforced by assertion only. Real modules never come
     * close (rustc emits shallow expression stacks); a hand-built module that pushes without
     * popping would otherwise double the array until it exhausts the heap.
     */
    private static final int MAX_STACK_SLOTS = 1 << 20;

    /**
     * Every frame shares one operand-stack array, so popping below the current frame's
     * {@code stackBase} silently reads the <em>caller's</em> slots instead of failing. There is
     * deliberately no wasm operand-type validator (the only input is rustc output, and host
     * safety is contained by bounds checks, caps and fuel), which leaves hand-built test modules
     * with a wrong stack depth as the real hazard. These assertions are live wherever such
     * modules exist — Gradle test JVMs run with {@code -ea} — and cost nothing in production.
     */
    private boolean canPop(int n) {
        return frameSp == 0 || sp - n >= frames[frameSp - 1].stackBase;
    }

    /** Drops {@code n} operand slots (the raw form of a pop whose value is unused). */
    private void dropSlots(int n) {
        assert canPop(n) : "operand stack underflow: pop of " + n + " below the frame base";
        sp -= n;
    }

    private void ensureStack() {
        if (sp == stack.length) {
            stack = Arrays.copyOf(stack, stack.length * 2);
        }
    }

    private void push(long v) {
        assert sp < MAX_STACK_SLOTS : "operand stack overflow past " + MAX_STACK_SLOTS + " slots";
        ensureStack();
        stack[sp++] = v;
    }

    private long pop() {
        assert canPop(1) : "operand stack underflow: pop below the frame base";
        return stack[--sp];
    }

    private void pushI32(int v) {
        push(v);
    }

    private int popI32() {
        assert canPop(1) : "operand stack underflow: pop below the frame base";
        return (int) stack[--sp];
    }

    private void pushI64(long v) {
        push(v);
    }

    private long popI64() {
        assert canPop(1) : "operand stack underflow: pop below the frame base";
        return stack[--sp];
    }

    private void pushF32(float f) {
        push(Float.floatToRawIntBits(f));
    }

    private float popF32() {
        assert canPop(1) : "operand stack underflow: pop below the frame base";
        return Float.intBitsToFloat((int) stack[--sp]);
    }

    private void pushF64(double d) {
        push(Double.doubleToRawLongBits(d));
    }

    private double popF64() {
        assert canPop(1) : "operand stack underflow: pop below the frame base";
        return Double.longBitsToDouble(stack[--sp]);
    }

    /** Peeks the top slot without popping (bulk ops price themselves off it before consuming). */
    private long peek() {
        assert canPop(1) : "operand stack underflow: peek below the frame base";
        return stack[sp - 1];
    }

    // --- little-endian memory access ---

    private static int readI32(byte[] m, int a) {
        return (m[a] & 0xFF) | ((m[a + 1] & 0xFF) << 8) | ((m[a + 2] & 0xFF) << 16) | ((m[a + 3] & 0xFF) << 24);
    }

    private static long readI64(byte[] m, int a) {
        return (readI32(m, a) & 0xFFFFFFFFL) | ((long) readI32(m, a + 4) << 32);
    }

    private static void writeI32(byte[] m, int a, int v) {
        m[a] = (byte) v;
        m[a + 1] = (byte) (v >>> 8);
        m[a + 2] = (byte) (v >>> 16);
        m[a + 3] = (byte) (v >>> 24);
    }

    private static void writeI64(byte[] m, int a, long v) {
        writeI32(m, a, (int) v);
        writeI32(m, a + 4, (int) (v >>> 32));
    }

    // --- immediate decoding over the frame body (already validated: no overlong) ---

    private static int readVarU32(Frame f) {
        byte[] b = f.body;
        int p = f.pc;
        int result = 0;
        int shift = 0;
        int by;
        do {
            by = b[p++];
            result |= (by & 0x7F) << shift;
            shift += 7;
        } while ((by & 0x80) != 0);
        f.pc = p;
        return result;
    }

    private static int readVarS32(Frame f) {
        byte[] b = f.body;
        int p = f.pc;
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
        f.pc = p;
        return result;
    }

    private static long readVarS64(Frame f) {
        byte[] b = f.body;
        int p = f.pc;
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
        f.pc = p;
        return result;
    }

    private static void skipBlockType(Frame f) {
        // A block type is a single-byte 0x40/valtype or a signed-LEB type index; the
        // continuation bit lets us skip it uniformly.
        while ((f.body[f.pc++] & 0x80) != 0) {
            // advance
        }
    }

    // --- the dispatch loop ---

    ExecResult run(long fuelBudget) {
        Frame f = frames[frameSp - 1];
        byte[] body = f.body;
        fuelConsumedThisRun = 0;
        fuel = fuelBudget;

        while (true) {
            if (fuel <= 0) {
                status = Status.RUNNABLE;
                return new ExecResult.FuelExhausted();
            }
            fuel--;
            fuelConsumedThisRun++;

            int pc = f.pc;
            int op = body[pc] & 0xFF;
            f.pc = pc + 1;

            switch (op) {
                case 0x00 -> { // unreachable
                    return trap(TrapReason.UNREACHABLE, "unreachable");
                }
                case 0x01 -> { } // nop
                case 0x02 -> {   // block
                    SideTable.Block blk = f.sideTable.block(pc);
                    skipBlockType(f);
                    pushLabel(f, sp - blk.paramCount(), blk.endPc(), blk.resultCount(), blk.endPc());
                }
                case 0x03 -> {   // loop
                    SideTable.Block blk = f.sideTable.block(pc);
                    skipBlockType(f);
                    int bodyPc = f.pc;
                    pushLabel(f, sp - blk.paramCount(), bodyPc, blk.paramCount(), blk.endPc());
                }
                case 0x04 -> {   // if
                    SideTable.Block blk = f.sideTable.block(pc);
                    skipBlockType(f);
                    int bodyPc = f.pc;
                    int cond = popI32();
                    pushLabel(f, sp - blk.paramCount(), blk.endPc(), blk.resultCount(), blk.endPc());
                    if (cond != 0) {
                        f.pc = bodyPc;
                    } else if (blk.elsePc() >= 0) {
                        f.pc = blk.elsePc();
                    } else {
                        f.pc = blk.endPc();
                        f.labelSp--; // no else: block is done
                    }
                }
                case 0x05 -> {   // else (reached only after the then-branch fell through)
                    int lb = (f.labelSp - 1) * 4;
                    f.pc = f.labels[lb + 3]; // endPc
                    f.labelSp--;
                }
                case 0x0B -> {   // end
                    f.labelSp--;
                    if (f.labelSp == 0) {
                        ExecResult done = returnFromCurrentFrame();
                        if (done != null) {
                            return done;
                        }
                        f = frames[frameSp - 1];
                        body = f.body;
                    }
                }
                case 0x0C -> {   // br
                    int depth = readVarU32(f);
                    ExecResult done = branch(f, depth);
                    if (done != null) {
                        return done;
                    }
                    // A br to the function-level label returns from a called function;
                    // branch() popped the frame (returning null for a non-entry frame),
                    // so refresh f/body to the caller before continuing.
                    f = frames[frameSp - 1];
                    body = f.body;
                }
                case 0x0D -> {   // br_if
                    int depth = readVarU32(f);
                    if (popI32() != 0) {
                        ExecResult done = branch(f, depth);
                        if (done != null) {
                            return done;
                        }
                        f = frames[frameSp - 1];
                        body = f.body;
                    }
                }
                case 0x0E -> {   // br_table
                    int count = readVarU32(f);
                    int index = popI32();
                    // Clamp an out-of-range index to the default (the last label).
                    int sel = Integer.compareUnsigned(index, count) < 0 ? index : count;
                    int depth = -1;
                    for (int i = 0; i <= count; i++) {
                        int d = readVarU32(f);
                        if (i == sel) {
                            depth = d;
                        }
                    }
                    ExecResult done = branch(f, depth);
                    if (done != null) {
                        return done;
                    }
                    f = frames[frameSp - 1];
                    body = f.body;
                }
                case 0x0F -> {   // return
                    int keep = f.resultArity;
                    int base = f.labels[0];
                    assert sp - keep >= base : "return: fewer results on the stack than declared";
                    System.arraycopy(stack, sp - keep, stack, base, keep);
                    sp = base + keep;
                    f.labelSp = 0;
                    ExecResult done = returnFromCurrentFrame();
                    if (done != null) {
                        return done;
                    }
                    f = frames[frameSp - 1];
                    body = f.body;
                }
                case 0x10 -> {   // call
                    int fi = readVarU32(f);
                    ExecResult r = doCall(fi);
                    if (r != null) {
                        return r;
                    }
                    f = frames[frameSp - 1];
                    body = f.body;
                }
                case 0x11 -> {   // call_indirect
                    int typeIdx = readVarU32(f);
                    int tableIdx = readVarU32(f);
                    int elem = popI32();
                    int[] table = tables[tableIdx];
                    if (Integer.compareUnsigned(elem, table.length) >= 0) {
                        return trap(TrapReason.UNDEFINED_ELEMENT, "call_indirect index out of table bounds");
                    }
                    int fi = table[elem];
                    if (fi < 0) {
                        return trap(TrapReason.UNINITIALIZED_ELEMENT, "call_indirect null element");
                    }
                    if (!instance.module().functionType(fi).equals(instance.module().types().get(typeIdx))) {
                        return trap(TrapReason.INDIRECT_CALL_TYPE_MISMATCH, "call_indirect signature mismatch");
                    }
                    ExecResult r = doCall(fi);
                    if (r != null) {
                        return r;
                    }
                    f = frames[frameSp - 1];
                    body = f.body;
                }
                case 0x1A -> dropSlots(1);  // drop
                case 0x1B -> {            // select
                    int c = popI32();
                    long b = pop();
                    long a = pop();
                    push(c != 0 ? a : b);
                }
                case 0x1C -> {            // select t*
                    int count = readVarU32(f);
                    f.pc += count; // skip the value types
                    int c = popI32();
                    long b = pop();
                    long a = pop();
                    push(c != 0 ? a : b);
                }
                case 0x20 -> push(f.locals[readVarU32(f)]);          // local.get
                case 0x21 -> f.locals[readVarU32(f)] = pop();        // local.set
                case 0x22 -> {                                       // local.tee
                    long v = peek();
                    f.locals[readVarU32(f)] = v;
                }
                case 0x23 -> push(globals[readVarU32(f)]);           // global.get
                case 0x24 -> globals[readVarU32(f)] = pop();         // global.set
                case 0x25 -> {                                       // table.get
                    int[] t = tables[readVarU32(f)];
                    int i = popI32();
                    if (Integer.compareUnsigned(i, t.length) >= 0) {
                        return trap(TrapReason.OUT_OF_BOUNDS_TABLE_ACCESS, "table.get out of bounds");
                    }
                    pushI32(t[i]);
                }
                case 0x26 -> {                                       // table.set
                    int[] t = tables[readVarU32(f)];
                    int v = popI32();
                    int i = popI32();
                    if (Integer.compareUnsigned(i, t.length) >= 0) {
                        return trap(TrapReason.OUT_OF_BOUNDS_TABLE_ACCESS, "table.set out of bounds");
                    }
                    t[i] = v;
                }
                // --- loads ---
                case 0x28 -> { byte[] m = region(ea(f), 4); if (m == null) return trapMem(); pushI32(readI32(m, regionOffset)); }
                case 0x29 -> { byte[] m = region(ea(f), 8); if (m == null) return trapMem(); pushI64(readI64(m, regionOffset)); }
                case 0x2A -> { byte[] m = region(ea(f), 4); if (m == null) return trapMem(); pushF32(Float.intBitsToFloat(readI32(m, regionOffset))); }
                case 0x2B -> { byte[] m = region(ea(f), 8); if (m == null) return trapMem(); pushI64(readI64(m, regionOffset)); }
                case 0x2C -> { byte[] m = region(ea(f), 1); if (m == null) return trapMem(); pushI32(m[regionOffset]); }
                case 0x2D -> { byte[] m = region(ea(f), 1); if (m == null) return trapMem(); pushI32(m[regionOffset] & 0xFF); }
                case 0x2E -> { byte[] m = region(ea(f), 2); if (m == null) return trapMem(); pushI32((short) load16(m, regionOffset)); }
                case 0x2F -> { byte[] m = region(ea(f), 2); if (m == null) return trapMem(); pushI32(load16(m, regionOffset)); }
                case 0x30 -> { byte[] m = region(ea(f), 1); if (m == null) return trapMem(); pushI64(m[regionOffset]); }
                case 0x31 -> { byte[] m = region(ea(f), 1); if (m == null) return trapMem(); pushI64(m[regionOffset] & 0xFF); }
                case 0x32 -> { byte[] m = region(ea(f), 2); if (m == null) return trapMem(); pushI64((short) load16(m, regionOffset)); }
                case 0x33 -> { byte[] m = region(ea(f), 2); if (m == null) return trapMem(); pushI64(load16(m, regionOffset)); }
                case 0x34 -> { byte[] m = region(ea(f), 4); if (m == null) return trapMem(); pushI64(readI32(m, regionOffset)); }
                case 0x35 -> { byte[] m = region(ea(f), 4); if (m == null) return trapMem(); pushI64(readI32(m, regionOffset) & 0xFFFFFFFFL); }
                // --- stores ---
                case 0x36 -> { int v = popI32(); byte[] m = region(ea(f), 4); if (m == null) return trapMem(); writeI32(m, regionOffset, v); }
                case 0x37 -> { long v = popI64(); byte[] m = region(ea(f), 8); if (m == null) return trapMem(); writeI64(m, regionOffset, v); }
                case 0x38 -> { int v = popI32(); byte[] m = region(ea(f), 4); if (m == null) return trapMem(); writeI32(m, regionOffset, v); } // f32.store (raw bits)
                case 0x39 -> { long v = popI64(); byte[] m = region(ea(f), 8); if (m == null) return trapMem(); writeI64(m, regionOffset, v); } // f64.store (raw bits)
                case 0x3A -> { int v = popI32(); byte[] m = region(ea(f), 1); if (m == null) return trapMem(); m[regionOffset] = (byte) v; }
                case 0x3B -> { int v = popI32(); byte[] m = region(ea(f), 2); if (m == null) return trapMem(); store16(m, regionOffset, v); }
                case 0x3C -> { long v = popI64(); byte[] m = region(ea(f), 1); if (m == null) return trapMem(); m[regionOffset] = (byte) v; }
                case 0x3D -> { long v = popI64(); byte[] m = region(ea(f), 2); if (m == null) return trapMem(); store16(m, regionOffset, (int) v); }
                case 0x3E -> { long v = popI64(); byte[] m = region(ea(f), 4); if (m == null) return trapMem(); writeI32(m, regionOffset, (int) v); }
                case 0x3F -> { readVarU32(f); pushI32(memPages); }   // memory.size
                case 0x40 -> {                                       // memory.grow
                    readVarU32(f);
                    long d = peek() & 0xFFFFFFFFL; // peek delta; don't pop until affordable
                    long np = memPages + d;
                    if (np > memMaxPages) {
                        dropSlots(1);
                        pushI32(-1); // failure is cheap: no allocation
                    } else if (!afford(1 + d * (PAGE >>> 4), pc, f)) {
                        return new ExecResult.FuelExhausted();
                    } else {
                        dropSlots(1);
                        int old = memPages;
                        growMemoryUnchecked((int) np);
                        pushI32(old);
                    }
                }
                case 0x41 -> pushI32(readVarS32(f)); // i32.const
                case 0x42 -> pushI64(readVarS64(f)); // i64.const
                case 0x43 -> pushF32(Float.intBitsToFloat(readRawI32(f)));   // f32.const
                case 0x44 -> pushF64(Double.longBitsToDouble(readRawI64(f))); // f64.const
                // --- comparisons ---
                case 0x45 -> pushI32(popI32() == 0 ? 1 : 0);
                case 0x46 -> { int b = popI32(); int a = popI32(); pushI32(a == b ? 1 : 0); }
                case 0x47 -> { int b = popI32(); int a = popI32(); pushI32(a != b ? 1 : 0); }
                case 0x48 -> { int b = popI32(); int a = popI32(); pushI32(a < b ? 1 : 0); }
                case 0x49 -> { int b = popI32(); int a = popI32(); pushI32(Integer.compareUnsigned(a, b) < 0 ? 1 : 0); }
                case 0x4A -> { int b = popI32(); int a = popI32(); pushI32(a > b ? 1 : 0); }
                case 0x4B -> { int b = popI32(); int a = popI32(); pushI32(Integer.compareUnsigned(a, b) > 0 ? 1 : 0); }
                case 0x4C -> { int b = popI32(); int a = popI32(); pushI32(a <= b ? 1 : 0); }
                case 0x4D -> { int b = popI32(); int a = popI32(); pushI32(Integer.compareUnsigned(a, b) <= 0 ? 1 : 0); }
                case 0x4E -> { int b = popI32(); int a = popI32(); pushI32(a >= b ? 1 : 0); }
                case 0x4F -> { int b = popI32(); int a = popI32(); pushI32(Integer.compareUnsigned(a, b) >= 0 ? 1 : 0); }
                case 0x50 -> pushI32(popI64() == 0 ? 1 : 0);
                case 0x51 -> { long b = popI64(); long a = popI64(); pushI32(a == b ? 1 : 0); }
                case 0x52 -> { long b = popI64(); long a = popI64(); pushI32(a != b ? 1 : 0); }
                case 0x53 -> { long b = popI64(); long a = popI64(); pushI32(a < b ? 1 : 0); }
                case 0x54 -> { long b = popI64(); long a = popI64(); pushI32(Long.compareUnsigned(a, b) < 0 ? 1 : 0); }
                case 0x55 -> { long b = popI64(); long a = popI64(); pushI32(a > b ? 1 : 0); }
                case 0x56 -> { long b = popI64(); long a = popI64(); pushI32(Long.compareUnsigned(a, b) > 0 ? 1 : 0); }
                case 0x57 -> { long b = popI64(); long a = popI64(); pushI32(a <= b ? 1 : 0); }
                case 0x58 -> { long b = popI64(); long a = popI64(); pushI32(Long.compareUnsigned(a, b) <= 0 ? 1 : 0); }
                case 0x59 -> { long b = popI64(); long a = popI64(); pushI32(a >= b ? 1 : 0); }
                case 0x5A -> { long b = popI64(); long a = popI64(); pushI32(Long.compareUnsigned(a, b) >= 0 ? 1 : 0); }
                case 0x5B -> { float b = popF32(); float a = popF32(); pushI32(a == b ? 1 : 0); }
                case 0x5C -> { float b = popF32(); float a = popF32(); pushI32(a != b ? 1 : 0); }
                case 0x5D -> { float b = popF32(); float a = popF32(); pushI32(a < b ? 1 : 0); }
                case 0x5E -> { float b = popF32(); float a = popF32(); pushI32(a > b ? 1 : 0); }
                case 0x5F -> { float b = popF32(); float a = popF32(); pushI32(a <= b ? 1 : 0); }
                case 0x60 -> { float b = popF32(); float a = popF32(); pushI32(a >= b ? 1 : 0); }
                case 0x61 -> { double b = popF64(); double a = popF64(); pushI32(a == b ? 1 : 0); }
                case 0x62 -> { double b = popF64(); double a = popF64(); pushI32(a != b ? 1 : 0); }
                case 0x63 -> { double b = popF64(); double a = popF64(); pushI32(a < b ? 1 : 0); }
                case 0x64 -> { double b = popF64(); double a = popF64(); pushI32(a > b ? 1 : 0); }
                case 0x65 -> { double b = popF64(); double a = popF64(); pushI32(a <= b ? 1 : 0); }
                case 0x66 -> { double b = popF64(); double a = popF64(); pushI32(a >= b ? 1 : 0); }
                // --- i32 arithmetic ---
                case 0x67 -> pushI32(Integer.numberOfLeadingZeros(popI32()));
                case 0x68 -> pushI32(Integer.numberOfTrailingZeros(popI32()));
                case 0x69 -> pushI32(Integer.bitCount(popI32()));
                case 0x6A -> { int b = popI32(); int a = popI32(); pushI32(a + b); }
                case 0x6B -> { int b = popI32(); int a = popI32(); pushI32(a - b); }
                case 0x6C -> { int b = popI32(); int a = popI32(); pushI32(a * b); }
                case 0x6D -> { int b = popI32(); int a = popI32();
                    if (b == 0) return trap(TrapReason.INTEGER_DIVIDE_BY_ZERO, "i32.div_s");
                    if (a == Integer.MIN_VALUE && b == -1) return trap(TrapReason.INTEGER_OVERFLOW, "i32.div_s");
                    pushI32(a / b); }
                case 0x6E -> { int b = popI32(); int a = popI32();
                    if (b == 0) return trap(TrapReason.INTEGER_DIVIDE_BY_ZERO, "i32.div_u");
                    pushI32(Integer.divideUnsigned(a, b)); }
                case 0x6F -> { int b = popI32(); int a = popI32();
                    if (b == 0) return trap(TrapReason.INTEGER_DIVIDE_BY_ZERO, "i32.rem_s");
                    pushI32(b == -1 ? 0 : a % b); }
                case 0x70 -> { int b = popI32(); int a = popI32();
                    if (b == 0) return trap(TrapReason.INTEGER_DIVIDE_BY_ZERO, "i32.rem_u");
                    pushI32(Integer.remainderUnsigned(a, b)); }
                case 0x71 -> { int b = popI32(); int a = popI32(); pushI32(a & b); }
                case 0x72 -> { int b = popI32(); int a = popI32(); pushI32(a | b); }
                case 0x73 -> { int b = popI32(); int a = popI32(); pushI32(a ^ b); }
                case 0x74 -> { int b = popI32(); int a = popI32(); pushI32(a << (b & 31)); }
                case 0x75 -> { int b = popI32(); int a = popI32(); pushI32(a >> (b & 31)); }
                case 0x76 -> { int b = popI32(); int a = popI32(); pushI32(a >>> (b & 31)); }
                case 0x77 -> { int b = popI32(); int a = popI32(); pushI32(Integer.rotateLeft(a, b)); }
                case 0x78 -> { int b = popI32(); int a = popI32(); pushI32(Integer.rotateRight(a, b)); }
                // --- i64 arithmetic ---
                case 0x79 -> pushI64(Long.numberOfLeadingZeros(popI64()));
                case 0x7A -> pushI64(Long.numberOfTrailingZeros(popI64()));
                case 0x7B -> pushI64(Long.bitCount(popI64()));
                case 0x7C -> { long b = popI64(); long a = popI64(); pushI64(a + b); }
                case 0x7D -> { long b = popI64(); long a = popI64(); pushI64(a - b); }
                case 0x7E -> { long b = popI64(); long a = popI64(); pushI64(a * b); }
                case 0x7F -> { long b = popI64(); long a = popI64();
                    if (b == 0) return trap(TrapReason.INTEGER_DIVIDE_BY_ZERO, "i64.div_s");
                    if (a == Long.MIN_VALUE && b == -1) return trap(TrapReason.INTEGER_OVERFLOW, "i64.div_s");
                    pushI64(a / b); }
                case 0x80 -> { long b = popI64(); long a = popI64();
                    if (b == 0) return trap(TrapReason.INTEGER_DIVIDE_BY_ZERO, "i64.div_u");
                    pushI64(Long.divideUnsigned(a, b)); }
                case 0x81 -> { long b = popI64(); long a = popI64();
                    if (b == 0) return trap(TrapReason.INTEGER_DIVIDE_BY_ZERO, "i64.rem_s");
                    pushI64(b == -1 ? 0 : a % b); }
                case 0x82 -> { long b = popI64(); long a = popI64();
                    if (b == 0) return trap(TrapReason.INTEGER_DIVIDE_BY_ZERO, "i64.rem_u");
                    pushI64(Long.remainderUnsigned(a, b)); }
                case 0x83 -> { long b = popI64(); long a = popI64(); pushI64(a & b); }
                case 0x84 -> { long b = popI64(); long a = popI64(); pushI64(a | b); }
                case 0x85 -> { long b = popI64(); long a = popI64(); pushI64(a ^ b); }
                case 0x86 -> { long b = popI64(); long a = popI64(); pushI64(a << (int) (b & 63)); }
                case 0x87 -> { long b = popI64(); long a = popI64(); pushI64(a >> (int) (b & 63)); }
                case 0x88 -> { long b = popI64(); long a = popI64(); pushI64(a >>> (int) (b & 63)); }
                case 0x89 -> { long b = popI64(); long a = popI64(); pushI64(Long.rotateLeft(a, (int) b)); }
                case 0x8A -> { long b = popI64(); long a = popI64(); pushI64(Long.rotateRight(a, (int) b)); }
                // --- f32 arithmetic ---
                case 0x8B -> pushF32(Math.abs(popF32()));
                case 0x8C -> pushF32(-popF32());
                case 0x8D -> pushF32((float) Math.ceil(popF32()));
                case 0x8E -> pushF32((float) Math.floor(popF32()));
                case 0x8F -> pushF32(ftrunc(popF32()));
                case 0x90 -> pushF32((float) Math.rint(popF32()));
                case 0x91 -> pushF32((float) Math.sqrt(popF32()));
                case 0x92 -> { float b = popF32(); float a = popF32(); pushF32(a + b); }
                case 0x93 -> { float b = popF32(); float a = popF32(); pushF32(a - b); }
                case 0x94 -> { float b = popF32(); float a = popF32(); pushF32(a * b); }
                case 0x95 -> { float b = popF32(); float a = popF32(); pushF32(a / b); }
                case 0x96 -> { float b = popF32(); float a = popF32(); pushF32(fmin(a, b)); }
                case 0x97 -> { float b = popF32(); float a = popF32(); pushF32(fmax(a, b)); }
                case 0x98 -> { float b = popF32(); float a = popF32(); pushF32(Math.copySign(a, b)); }
                // --- f64 arithmetic ---
                case 0x99 -> pushF64(Math.abs(popF64()));
                case 0x9A -> pushF64(-popF64());
                case 0x9B -> pushF64(Math.ceil(popF64()));
                case 0x9C -> pushF64(Math.floor(popF64()));
                case 0x9D -> pushF64(dtrunc(popF64()));
                case 0x9E -> pushF64(Math.rint(popF64()));
                case 0x9F -> pushF64(Math.sqrt(popF64()));
                case 0xA0 -> { double b = popF64(); double a = popF64(); pushF64(a + b); }
                case 0xA1 -> { double b = popF64(); double a = popF64(); pushF64(a - b); }
                case 0xA2 -> { double b = popF64(); double a = popF64(); pushF64(a * b); }
                case 0xA3 -> { double b = popF64(); double a = popF64(); pushF64(a / b); }
                case 0xA4 -> { double b = popF64(); double a = popF64(); pushF64(dmin(a, b)); }
                case 0xA5 -> { double b = popF64(); double a = popF64(); pushF64(dmax(a, b)); }
                case 0xA6 -> { double b = popF64(); double a = popF64(); pushF64(Math.copySign(a, b)); }
                // --- conversions ---
                case 0xA7 -> pushI32((int) popI64()); // i32.wrap_i64
                case 0xA8 -> { float x = popF32(); if (Float.isNaN(x)) return trap(TrapReason.INVALID_CONVERSION_TO_INTEGER, "i32.trunc_f32_s");
                    double d = x; if (d <= -2147483649.0 || d >= 2147483648.0) return trap(TrapReason.INTEGER_OVERFLOW, "i32.trunc_f32_s"); pushI32((int) x); }
                case 0xA9 -> { float x = popF32(); if (Float.isNaN(x)) return trap(TrapReason.INVALID_CONVERSION_TO_INTEGER, "i32.trunc_f32_u");
                    double d = x; if (d <= -1.0 || d >= 4294967296.0) return trap(TrapReason.INTEGER_OVERFLOW, "i32.trunc_f32_u"); pushI32((int) (long) d); }
                case 0xAA -> { double x = popF64(); if (Double.isNaN(x)) return trap(TrapReason.INVALID_CONVERSION_TO_INTEGER, "i32.trunc_f64_s");
                    if (x <= -2147483649.0 || x >= 2147483648.0) return trap(TrapReason.INTEGER_OVERFLOW, "i32.trunc_f64_s"); pushI32((int) x); }
                case 0xAB -> { double x = popF64(); if (Double.isNaN(x)) return trap(TrapReason.INVALID_CONVERSION_TO_INTEGER, "i32.trunc_f64_u");
                    if (x <= -1.0 || x >= 4294967296.0) return trap(TrapReason.INTEGER_OVERFLOW, "i32.trunc_f64_u"); pushI32((int) (long) x); }
                case 0xAC -> pushI64(popI32());                       // i64.extend_i32_s
                case 0xAD -> pushI64(popI32() & 0xFFFFFFFFL);         // i64.extend_i32_u
                case 0xAE -> { float x = popF32(); if (Float.isNaN(x)) return trap(TrapReason.INVALID_CONVERSION_TO_INTEGER, "i64.trunc_f32_s");
                    if (x < -9.223372036854776E18 || x >= 9.223372036854776E18) return trap(TrapReason.INTEGER_OVERFLOW, "i64.trunc_f32_s"); pushI64((long) x); }
                case 0xAF -> { float x = popF32(); if (Float.isNaN(x)) return trap(TrapReason.INVALID_CONVERSION_TO_INTEGER, "i64.trunc_f32_u");
                    if (x <= -1.0f || x >= 1.8446744073709552E19) return trap(TrapReason.INTEGER_OVERFLOW, "i64.trunc_f32_u"); pushI64(unsignedTrunc(x)); }
                case 0xB0 -> { double x = popF64(); if (Double.isNaN(x)) return trap(TrapReason.INVALID_CONVERSION_TO_INTEGER, "i64.trunc_f64_s");
                    if (x < -9.223372036854776E18 || x >= 9.223372036854776E18) return trap(TrapReason.INTEGER_OVERFLOW, "i64.trunc_f64_s"); pushI64((long) x); }
                case 0xB1 -> { double x = popF64(); if (Double.isNaN(x)) return trap(TrapReason.INVALID_CONVERSION_TO_INTEGER, "i64.trunc_f64_u");
                    if (x <= -1.0 || x >= 1.8446744073709552E19) return trap(TrapReason.INTEGER_OVERFLOW, "i64.trunc_f64_u"); pushI64(unsignedTrunc(x)); }
                case 0xB2 -> pushF32((float) popI32());               // f32.convert_i32_s
                case 0xB3 -> pushF32((float) (popI32() & 0xFFFFFFFFL)); // f32.convert_i32_u
                case 0xB4 -> pushF32((float) popI64());               // f32.convert_i64_s
                case 0xB5 -> pushF32(ulongToFloat(popI64()));         // f32.convert_i64_u
                case 0xB6 -> pushF32((float) popF64());               // f32.demote_f64
                case 0xB7 -> pushF64(popI32());                       // f64.convert_i32_s
                case 0xB8 -> pushF64((double) (popI32() & 0xFFFFFFFFL)); // f64.convert_i32_u (u32 -> exact double)
                case 0xB9 -> pushF64((double) popI64());              // f64.convert_i64_s
                case 0xBA -> pushF64(ulongToDouble(popI64()));        // f64.convert_i64_u
                case 0xBB -> pushF64(popF32());                       // f64.promote_f32
                case 0xBC -> pushI32(Float.floatToRawIntBits(popF32())); // i32.reinterpret_f32
                case 0xBD -> pushI64(Double.doubleToRawLongBits(popF64())); // i64.reinterpret_f64
                case 0xBE -> pushF32(Float.intBitsToFloat(popI32()));  // f32.reinterpret_i32
                case 0xBF -> pushF64(Double.longBitsToDouble(popI64())); // f64.reinterpret_i64
                // --- sign extension ---
                case 0xC0 -> pushI32((byte) popI32());
                case 0xC1 -> pushI32((short) popI32());
                case 0xC2 -> pushI64((byte) popI64());
                case 0xC3 -> pushI64((short) popI64());
                case 0xC4 -> pushI64((long) (int) popI64()); // sign-extend low 32 bits
                // --- reference types ---
                case 0xD0 -> { f.pc++; pushI32(-1); }                 // ref.null t
                case 0xD1 -> pushI32(popI32() < 0 ? 1 : 0);           // ref.is_null
                case 0xD2 -> pushI32(readVarU32(f));                  // ref.func
                case 0xFC -> {
                    ExecResult r = fcPrefixed(f, pc);
                    if (r != null) {
                        return r;
                    }
                }
                default -> throw new IllegalStateException(
                        "unreachable: opcode 0x" + Integer.toHexString(op) + " reached the interpreter but "
                                + "should have been rejected by the parser");
            }
        }
    }

    // --- helpers shared by the dispatch loop ---

    /** Effective address for a load/store: reads the memarg, pops the base. */
    private long ea(Frame f) {
        readVarU32(f); // align (ignored)
        long off = readVarU32(f) & 0xFFFFFFFFL;
        return (popI32() & 0xFFFFFFFFL) + off;
    }

    /**
     * Routes {@code [addr, addr+size)} to the byte array backing it, leaving the offset within
     * that array in {@link #regionOffset}; {@code null} means out of bounds (the caller traps).
     *
     * <p>One compare on the hot path: below {@link SharedRegion#SHARED_BASE} is this task's
     * private memory, at or above it the instance's shared region. A range can never straddle
     * the two — private memory ends far below the base — so a bulk op that would cross the
     * boundary just runs off the end of the region it started in and traps, which is exactly
     * the rule the shared region needs.
     */
    private byte[] region(long addr, long size) {
        if (addr < SharedRegion.SHARED_BASE) {
            if (addr + size > (long) memPages * PAGE) {
                return null;
            }
            regionOffset = (int) addr;
            return memory;
        }
        long off = addr - SharedRegion.SHARED_BASE;
        if (off + size > shared.size()) {
            return null;
        }
        regionOffset = (int) off;
        return shared.bytes();
    }

    private static int load16(byte[] m, int a) {
        return (m[a] & 0xFF) | ((m[a + 1] & 0xFF) << 8);
    }

    private static void store16(byte[] m, int a, int v) {
        m[a] = (byte) v;
        m[a + 1] = (byte) (v >>> 8);
    }

    private ExecResult trapMem() {
        return trap(TrapReason.OUT_OF_BOUNDS_MEMORY_ACCESS, "out of bounds memory access");
    }

    private int readRawI32(Frame f) {
        byte[] b = f.body;
        int p = f.pc;
        int v = (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8) | ((b[p + 2] & 0xFF) << 16) | ((b[p + 3] & 0xFF) << 24);
        f.pc = p + 4;
        return v;
    }

    private long readRawI64(Frame f) {
        long lo = readRawI32(f) & 0xFFFFFFFFL;
        long hi = readRawI32(f) & 0xFFFFFFFFL;
        return lo | (hi << 32);
    }

    /**
     * Branch to label {@code depth} using the runtime label stack. The label records
     * its operand-stack base, jump target, keep count and endPc; a target that differs
     * from endPc is a loop back-edge (stay in the loop), otherwise the target block is
     * exited. Reaching depth 0 (the function label) is a return.
     */
    private ExecResult branch(Frame f, int depth) {
        int idx = f.labelSp - 1 - depth;
        int lb = idx * 4;
        int base = f.labels[lb];
        int targetPc = f.labels[lb + 1];
        int keep = f.labels[lb + 2];
        int endPc = f.labels[lb + 3];
        assert sp - keep >= base : "branch: fewer kept operands on the stack than the label wants";
        System.arraycopy(stack, sp - keep, stack, base, keep);
        sp = base + keep;
        if (targetPc != endPc) { // loop back-edge
            f.labelSp = idx + 1;
            f.pc = targetPc;
            return null;
        }
        f.labelSp = idx; // exit through the target block
        if (f.labelSp == 0) {
            return returnFromCurrentFrame();
        }
        f.pc = targetPc;
        return null;
    }

    /**
     * Charges the extra fuel a bulk-memory / grow instruction costs beyond the base 1
     * already spent this step (so the op is priced by the work it does, not a flat 1).
     * Returns {@code true} if affordable (fuel deducted). If not, it rewinds the program
     * counter to {@code opPc}, refunds the base charge, mutates and pops nothing, and
     * returns {@code false} — the caller returns {@link ExecResult.FuelExhausted}, so a
     * later resume with more fuel re-executes the whole instruction from scratch. This is
     * what keeps such ops resume-safe: no partial writes and no double-pops, ever.
     */
    private boolean afford(long totalCost, int opPc, Frame f) {
        long extra = totalCost - 1;
        if (extra <= 0) {
            return true;
        }
        if (fuel >= extra) {
            fuel -= extra;
            fuelConsumedThisRun += extra;
            return true;
        }
        f.pc = opPc;
        fuel += 1;
        fuelConsumedThisRun -= 1;
        status = Status.RUNNABLE;
        return false;
    }

    /** Pops the current frame; returns a Completed result if it was the outermost, else null. */
    private ExecResult returnFromCurrentFrame() {
        Frame done = frames[frameSp - 1];
        frameSp--;
        if (frameSp == 0) {
            long[] res = new long[done.resultArity];
            assert sp >= done.stackBase + done.resultArity
                    : "return: the entry frame left fewer results than it declares";
            System.arraycopy(stack, done.stackBase, res, 0, done.resultArity);
            sp = done.stackBase;
            status = Status.COMPLETED;
            completedValues = res;
            return new ExecResult.Completed(res);
        }
        return null;
    }

    /** Invokes function {@code fi} (host or defined). Non-null return means resume must stop. */
    private ExecResult doCall(int fi) {
        Module module = instance.module();
        int importedCount = module.importedFunctionCount();
        FuncType type = module.functionType(fi);
        int np = type.params().size();
        if (fi < importedCount) {
            long[] args = new long[np];
            assert canPop(np) : "call: fewer than " + np + " argument(s) above the frame base";
            System.arraycopy(stack, sp - np, args, 0, np);
            sp -= np;
            HostFunction hf = instance.hostFunction(fi);
            try {
                long ret = hf.invoke(this, args);
                if (type.results().size() == 1) {
                    push(ret);
                }
                return null;
            } catch (SuspendSignal s) {
                suspendHostArity = type.results().size();
                suspendRequest = s.request();
                status = Status.SUSPENDED;
                return new ExecResult.Suspended(s.request());
            }
        }
        if (frameSp >= MAX_FRAMES) {
            return trap(TrapReason.CALL_STACK_EXHAUSTED, "maximum call depth exceeded");
        }
        FunctionCode code = module.code().get(fi - importedCount);
        Frame nf = newFrame(fi, type, code);
        assert canPop(np) : "call: fewer than " + np + " argument(s) above the frame base";
        System.arraycopy(stack, sp - np, nf.locals, 0, np);
        sp -= np;
        nf.stackBase = sp;
        pushFunctionLabel(nf);
        pushFrame(nf);
        return null;
    }

    private ExecResult trap(TrapReason reason, String detail) {
        status = Status.TRAPPED;
        return new ExecResult.Trapped(reason, reason.description() + ": " + detail);
    }

    // --- 0xFC-prefixed ops (saturating truncation + bulk memory/table) ---

    // Bulk copy/fill/init and grow are priced by the work they do (see afford): 1 fuel
    // per BULK_UNIT bytes/elements moved or allocated. The cost is computed from the
    // length operand PEEKED off the stack (never popped until affordable), so an
    // unaffordable op rewinds and re-executes cleanly on resume.
    private static final int BULK_UNIT_SHIFT = 4; // 16 bytes/elements per fuel unit

    private ExecResult fcPrefixed(Frame f, int opPc) {
        int sub = readVarU32(f);
        switch (sub) {
            case 0 -> pushI32(satTruncI32(popF32()));
            case 1 -> pushI32(satTruncU32(popF32()));
            case 2 -> pushI32(satTruncI32(popF64()));
            case 3 -> pushI32(satTruncU32(popF64()));
            case 4 -> pushI64(satTruncI64(popF32()));
            case 5 -> pushI64(satTruncU64(popF32()));
            case 6 -> pushI64(satTruncI64(popF64()));
            case 7 -> pushI64(satTruncU64(popF64()));
            case 8 -> { // memory.init dataidx memidx
                int dataIdx = readVarU32(f);
                readVarU32(f); // memidx (0)
                long len = peek() & 0xFFFFFFFFL; // peek
                if (!afford(1 + (len >>> BULK_UNIT_SHIFT), opPc, f)) {
                    return new ExecResult.FuelExhausted();
                }
                dropSlots(1); // consume len
                long src = popI32() & 0xFFFFFFFFL;
                long dst = popI32() & 0xFFFFFFFFL;
                byte[] seg = dataDropped[dataIdx] ? EMPTY : instance.dataSegment(dataIdx);
                byte[] d = region(dst, len);
                if (src + len > seg.length || d == null) {
                    return trapMem();
                }
                System.arraycopy(seg, (int) src, d, regionOffset, (int) len);
            }
            case 9 -> dataDropped[readVarU32(f)] = true; // data.drop
            case 10 -> { // memory.copy
                readVarU32(f);
                readVarU32(f);
                long len = peek() & 0xFFFFFFFFL; // peek
                if (!afford(1 + (len >>> BULK_UNIT_SHIFT), opPc, f)) {
                    return new ExecResult.FuelExhausted();
                }
                dropSlots(1);
                long src = popI32() & 0xFFFFFFFFL;
                long dst = popI32() & 0xFFFFFFFFL;
                // Each end is routed on its own, so private->shared and shared->private copies
                // both work; what neither end may do is straddle the boundary.
                byte[] s = region(src, len);
                if (s == null) {
                    return trapMem();
                }
                int srcOff = regionOffset;
                byte[] d = region(dst, len);
                if (d == null) {
                    return trapMem();
                }
                System.arraycopy(s, srcOff, d, regionOffset, (int) len);
            }
            case 11 -> { // memory.fill
                readVarU32(f);
                long len = peek() & 0xFFFFFFFFL; // peek
                if (!afford(1 + (len >>> BULK_UNIT_SHIFT), opPc, f)) {
                    return new ExecResult.FuelExhausted();
                }
                dropSlots(1);
                byte val = (byte) popI32();
                long dst = popI32() & 0xFFFFFFFFL;
                byte[] d = region(dst, len);
                if (d == null) {
                    return trapMem();
                }
                Arrays.fill(d, regionOffset, (int) (regionOffset + len), val);
            }
            case 12 -> { // table.init elemidx tableidx
                int elemIdx = readVarU32(f);
                int[] table = tables[readVarU32(f)];
                long len = peek() & 0xFFFFFFFFL; // peek
                if (!afford(1 + (len >>> BULK_UNIT_SHIFT), opPc, f)) {
                    return new ExecResult.FuelExhausted();
                }
                dropSlots(1);
                long src = popI32() & 0xFFFFFFFFL;
                long dst = popI32() & 0xFFFFFFFFL;
                int[] seg = elemDropped[elemIdx] ? EMPTY_INT : instance.elementSegment(elemIdx);
                if (src + len > seg.length || dst + len > table.length) {
                    return trap(TrapReason.OUT_OF_BOUNDS_TABLE_ACCESS, "table.init out of bounds");
                }
                System.arraycopy(seg, (int) src, table, (int) dst, (int) len);
            }
            case 13 -> elemDropped[readVarU32(f)] = true; // elem.drop
            case 14 -> { // table.copy dst src
                int[] dstTable = tables[readVarU32(f)];
                int[] srcTable = tables[readVarU32(f)];
                long len = peek() & 0xFFFFFFFFL; // peek
                if (!afford(1 + (len >>> BULK_UNIT_SHIFT), opPc, f)) {
                    return new ExecResult.FuelExhausted();
                }
                dropSlots(1);
                long src = popI32() & 0xFFFFFFFFL;
                long dst = popI32() & 0xFFFFFFFFL;
                if (src + len > srcTable.length || dst + len > dstTable.length) {
                    return trap(TrapReason.OUT_OF_BOUNDS_TABLE_ACCESS, "table.copy out of bounds");
                }
                System.arraycopy(srcTable, (int) src, dstTable, (int) dst, (int) len);
            }
            case 15 -> { // table.grow
                int ti = readVarU32(f);
                int[] table = tables[ti];
                long delta = peek() & 0xFFFFFFFFL; // peek delta (initVal is below it)
                long np = (table.length & 0xFFFFFFFFL) + delta;
                if (np > tableMax[ti]) {
                    dropSlots(2); // pop delta + initVal
                    pushI32(-1); // failure is cheap: no allocation
                } else if (!afford(1 + (np >>> BULK_UNIT_SHIFT), opPc, f)) {
                    return new ExecResult.FuelExhausted();
                } else {
                    dropSlots(1); // consume delta
                    int initVal = popI32();
                    int old = table.length;
                    int[] nt = Arrays.copyOf(table, (int) np);
                    Arrays.fill(nt, old, (int) np, initVal);
                    tables[ti] = nt;
                    pushI32(old);
                }
            }
            case 16 -> pushI32(tables[readVarU32(f)].length); // table.size
            case 17 -> { // table.fill
                int[] table = tables[readVarU32(f)];
                long len = peek() & 0xFFFFFFFFL; // peek
                if (!afford(1 + (len >>> BULK_UNIT_SHIFT), opPc, f)) {
                    return new ExecResult.FuelExhausted();
                }
                dropSlots(1);
                int val = popI32();
                long dst = popI32() & 0xFFFFFFFFL;
                if (dst + len > table.length) {
                    return trap(TrapReason.OUT_OF_BOUNDS_TABLE_ACCESS, "table.fill out of bounds");
                }
                Arrays.fill(table, (int) dst, (int) (dst + len), val);
            }
            default -> throw new IllegalStateException("unreachable: 0xFC " + sub);
        }
        return null;
    }

    private static final byte[] EMPTY = new byte[0];
    private static final int[] EMPTY_INT = new int[0];

    // --- numeric helpers ---

    private static float ftrunc(float x) {
        return (x < 0) ? (float) Math.ceil(x) : (float) Math.floor(x);
    }

    private static double dtrunc(double x) {
        return (x < 0) ? Math.ceil(x) : Math.floor(x);
    }

    private static float fmin(float a, float b) {
        if (Float.isNaN(a) || Float.isNaN(b)) {
            return Float.NaN;
        }
        if (a == 0.0f && b == 0.0f) {
            // -0.0 is the minimum of the two zeros.
            return (Float.floatToRawIntBits(a) | Float.floatToRawIntBits(b)) < 0 ? -0.0f : 0.0f;
        }
        return Math.min(a, b);
    }

    private static float fmax(float a, float b) {
        if (Float.isNaN(a) || Float.isNaN(b)) {
            return Float.NaN;
        }
        if (a == 0.0f && b == 0.0f) {
            // +0.0 is the maximum of the two zeros.
            return (Float.floatToRawIntBits(a) & Float.floatToRawIntBits(b)) < 0 ? -0.0f : 0.0f;
        }
        return Math.max(a, b);
    }

    private static double dmin(double a, double b) {
        if (Double.isNaN(a) || Double.isNaN(b)) {
            return Double.NaN;
        }
        if (a == 0.0 && b == 0.0) {
            return (Double.doubleToRawLongBits(a) | Double.doubleToRawLongBits(b)) < 0 ? -0.0 : 0.0;
        }
        return Math.min(a, b);
    }

    private static double dmax(double a, double b) {
        if (Double.isNaN(a) || Double.isNaN(b)) {
            return Double.NaN;
        }
        if (a == 0.0 && b == 0.0) {
            return (Double.doubleToRawLongBits(a) & Double.doubleToRawLongBits(b)) < 0 ? -0.0 : 0.0;
        }
        return Math.max(a, b);
    }

    private static long unsignedTrunc(double x) {
        // Precondition: 0 <= trunc(x) < 2^64. Split at 2^63 to build the unsigned bits.
        if (x < 9.223372036854776E18) {
            return (long) x;
        }
        return ((long) (x - 9.223372036854776E18)) | Long.MIN_VALUE;
    }

    private static float ulongToFloat(long v) {
        return (float) ulongToDouble(v);
    }

    private static double ulongToDouble(long v) {
        if (v >= 0) {
            return v;
        }
        return ((double) (v >>> 1)) * 2.0 + (v & 1);
    }

    private static int satTruncI32(double x) {
        if (Double.isNaN(x)) {
            return 0;
        }
        if (x < -2147483648.0) {
            return Integer.MIN_VALUE;
        }
        if (x > 2147483647.0) {
            return Integer.MAX_VALUE;
        }
        return (int) x;
    }

    private static int satTruncU32(double x) {
        if (Double.isNaN(x) || x < 0.0) {
            return 0;
        }
        if (x > 4294967295.0) {
            return -1; // 0xFFFFFFFF
        }
        return (int) (long) x;
    }

    private static long satTruncI64(double x) {
        if (Double.isNaN(x)) {
            return 0;
        }
        if (x < -9.223372036854776E18) {
            return Long.MIN_VALUE;
        }
        if (x >= 9.223372036854776E18) {
            return Long.MAX_VALUE;
        }
        return (long) x;
    }

    private static long satTruncU64(double x) {
        if (Double.isNaN(x) || x < 0.0) {
            return 0;
        }
        if (x >= 1.8446744073709552E19) {
            return -1L; // 0xFFFF...FFFF
        }
        return unsignedTrunc(x);
    }
}
