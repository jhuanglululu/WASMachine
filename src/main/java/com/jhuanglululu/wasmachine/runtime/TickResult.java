package com.jhuanglululu.wasmachine.runtime;

/**
 * The outcome of one instance tick step.
 *
 * <ul>
 *   <li>{@link Running} — the instance is still going; tick again next game tick.</li>
 *   <li>{@link Finished} — task 0's {@code main} returned; carries its raw {@code i32}
 *       exit value. The engine attaches no meaning to it — interpretation (e.g.
 *       Billboard's End/Keep/Repeat) is plugin semantics.</li>
 *   <li>{@link Errored} — a trap, {@code fail}, fuel/memory kill, or a host import
 *       abort ended it; carries a precise message. Nothing ever fails silently.</li>
 * </ul>
 *
 * {@link Finished} and {@link Errored} are terminal: further ticks return the same value.
 */
public sealed interface TickResult permits TickResult.Running, TickResult.Finished, TickResult.Errored {

    record Running() implements TickResult {}

    record Finished(int exitValue) implements TickResult {}

    record Errored(String message) implements TickResult {}
}
