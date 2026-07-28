package com.jhuanglululu.wasm;

import java.util.List;

/**
 * A function signature: parameter and result value types. Multi-value results are
 * permitted (they appear in block types even though rustc does not emit multi-value
 * functions).
 */
public record FuncType(List<ValType> params, List<ValType> results) {

    public FuncType {
        params = List.copyOf(params);
        results = List.copyOf(results);
    }
}
