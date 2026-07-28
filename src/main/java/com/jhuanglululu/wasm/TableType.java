package com.jhuanglululu.wasm;

/**
 * A table type: element reference type (always a reference type, typically
 * {@code funcref}) and its {@link Limits}.
 */
public record TableType(ValType elementType, Limits limits) {}
