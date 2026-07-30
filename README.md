# WASMachine

A from-scratch WASM interpreter + cooperative task runtime (Java) for
Minecraft plugins that run guest scripts compiled from Rust. Suspends guest
execution mid-call and resumes it ticks later — full lifecycle control no
off-the-shelf runtime offers.

The guest-side core crate lives in the sibling repo
[`wasmachine-rs`](https://github.com/jhuanglululu/wasmachine-rs); the two
halves of the ABI contract version together. Reference embedder:
[`Billboard`](https://github.com/jhuanglululu/Billboard).

Personal-use library: versioned by git, no publishing pipeline; consumed
as a Gradle source dependency on this repo (tracking `main`).
