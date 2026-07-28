package com.jhuanglululu.wasm;

/** A global's type: its value type and whether it is mutable. */
public record GlobalType(ValType valueType, boolean mutable) {}
