package com.jhuanglululu.wasm;

/**
 * A constant initializer expression (global init, active element/data offset).
 *
 * <p>The parser validates that the expression contains only constant instructions
 * ({@code i32/i64/f32/f64.const}, {@code global.get} of an imported global,
 * {@code ref.null}, {@code ref.func}) terminated by {@code end}, and captures the
 * raw instruction bytes <em>excluding</em> the terminating {@code end}. The
 * interpreter (added later) evaluates these bytes; the parser does not compute the
 * resulting value because {@code global.get} depends on runtime global state.
 */
public final class ConstExpr {

    private final byte[] bytes;

    public ConstExpr(byte[] bytes) {
        this.bytes = bytes.clone();
    }

    public byte[] bytes() {
        return bytes.clone();
    }
}
