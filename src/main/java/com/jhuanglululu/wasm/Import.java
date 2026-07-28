package com.jhuanglululu.wasm;

/**
 * An import: a {@code (module, name)} pair and a typed descriptor. Use
 * {@link #kind()} to switch, or pattern-match on {@link #descriptor()}.
 */
public record Import(String module, String name, Descriptor descriptor) {

    /** The imported entity's kind. */
    public ExternalKind kind() {
        return switch (descriptor) {
            case Func f -> ExternalKind.FUNCTION;
            case Table t -> ExternalKind.TABLE;
            case Memory m -> ExternalKind.MEMORY;
            case GlobalImport g -> ExternalKind.GLOBAL;
        };
    }

    /** A typed import descriptor. */
    public sealed interface Descriptor permits Func, Table, Memory, GlobalImport {}

    /** A function import referencing a type by index. */
    public record Func(int typeIndex) implements Descriptor {}

    /** A table import. */
    public record Table(TableType type) implements Descriptor {}

    /** A memory import. */
    public record Memory(Limits limits) implements Descriptor {}

    /** A global import. */
    public record GlobalImport(GlobalType type) implements Descriptor {}
}
