package com.jhuanglululu.wasm;

/** A defined global: its type and constant initializer expression. */
public record Global(GlobalType type, ConstExpr init) {}
