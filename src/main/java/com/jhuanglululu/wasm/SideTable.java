package com.jhuanglululu.wasm;

import java.util.Map;

/**
 * Precomputed control-flow metadata for one function body, so the interpreter
 * never scans the bytecode forward at runtime. All offsets are byte positions into
 * the function's instruction bytes ({@link FunctionCode#body()}), where offset 0 is
 * the first instruction (after the locals declaration).
 *
 * <h2>Format</h2>
 * Two lookup maps, both keyed by the byte offset of the instruction they describe:
 *
 * <ul>
 *   <li><b>{@link #blocks()}</b> — keyed by the offset of a {@code block}/{@code loop}/
 *       {@code if} opcode. Gives the interpreter, at the moment it enters a structured
 *       block, everything it needs without scanning: the matching {@code else} and
 *       {@code end} offsets and the block's parameter/result arity (from its block
 *       type). See {@link Block}.</li>
 *   <li><b>{@link #branches()}</b> — keyed by the offset of a {@code br}/{@code br_if}/
 *       {@code br_table} opcode. Gives the resolved jump target and stack-keep count
 *       for each label the branch can take. See {@link Branch}.</li>
 * </ul>
 *
 * <h2>How the interpreter uses it (stack-adjustment)</h2>
 * The interpreter maintains a control stack of active labels, recording each label's
 * operand-stack base height when the block is entered (base = height on entry minus
 * the block's {@code paramCount}). The sidetable supplies the two things that would
 * otherwise require a forward scan:
 * <ul>
 *   <li>the <b>target offset</b> to jump to — for a {@code block}/{@code if} label the
 *       matching {@code end}; for a {@code loop} label the loop's body start; for the
 *       implicit function-level label the end of the body (i.e. a return);</li>
 *   <li>the <b>keep count</b> — how many operand values the branch carries to the
 *       target (the target label's result arity for forward branches, its parameter
 *       arity for a {@code loop} back-edge).</li>
 * </ul>
 * The number of values to <em>discard</em> is derived at runtime from the recorded
 * label base height, so it needs no static type checking here.
 *
 * <h2>How it is built</h2>
 * A single structural pass over the body matches {@code block}/{@code loop}/{@code if}/
 * {@code else}/{@code end} with a stack of open frames. Back-edges to loops resolve
 * immediately (the loop body start is already known); forward branches to a block/if
 * are back-patched when the matching {@code end} is reached. This pass needs no
 * operand type information — only the structure and each block's declared arity.
 */
public final class SideTable {

    /** Which kind of structured instruction a {@link Block} describes. */
    public enum BlockKind {
        BLOCK,
        LOOP,
        IF
    }

    /**
     * Control metadata for one {@code block}/{@code loop}/{@code if}.
     *
     * @param kind        block, loop, or if
     * @param elsePc      offset of the first instruction after a matching {@code else},
     *                    or {@code -1} if the {@code if} has no {@code else} (or this is
     *                    not an {@code if}). The interpreter uses this to jump when an
     *                    {@code if} condition is false; if {@code -1}, it uses {@link #endPc}.
     * @param endPc       offset of the first instruction after the matching {@code end}
     * @param paramCount  number of values the block consumes on entry (block-type params)
     * @param resultCount number of values the block produces (block-type results)
     */
    public record Block(BlockKind kind, int elsePc, int endPc, int paramCount, int resultCount) {}

    /**
     * Resolved jump metadata for a branch. For {@code br}/{@code br_if} the arrays
     * have length 1; for {@code br_table} they have length {@code N + 1} (the {@code N}
     * table entries followed by the default target).
     *
     * @param targetPc jump target offset for each label
     * @param keep     number of operand values to keep (carry to the target) for each label
     */
    @SuppressWarnings("ArrayRecordComponent") // identity equality is fine; arrays are internal jump tables
    public record Branch(int[] targetPc, int[] keep) {}

    private final Map<Integer, Block> blocks;
    private final Map<Integer, Branch> branches;

    SideTable(Map<Integer, Block> blocks, Map<Integer, Branch> branches) {
        this.blocks = Map.copyOf(blocks);
        this.branches = Map.copyOf(branches);
    }

    /** Control metadata keyed by the offset of each {@code block}/{@code loop}/{@code if} opcode. */
    public Map<Integer, Block> blocks() {
        return blocks;
    }

    /** Branch metadata keyed by the offset of each {@code br}/{@code br_if}/{@code br_table} opcode. */
    public Map<Integer, Branch> branches() {
        return branches;
    }

    /** The control metadata for the block/loop/if at {@code offset}, or {@code null}. */
    public Block block(int offset) {
        return blocks.get(offset);
    }

    /** The branch metadata for the branch instruction at {@code offset}, or {@code null}. */
    public Branch branch(int offset) {
        return branches.get(offset);
    }
}
