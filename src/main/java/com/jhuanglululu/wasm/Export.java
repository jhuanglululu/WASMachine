package com.jhuanglululu.wasm;

/** An export: a name, the kind of entity, and its index in the matching index space. */
public record Export(String name, ExternalKind kind, int index) {}
