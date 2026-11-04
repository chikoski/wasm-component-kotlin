//! The generic Wasmtime bridge: runs any component through Wasmtime's dynamic `Val` API, without a
//! build step. Typed bridges, generated per project, export the same functions (ADR-0016).

wasm2kt_bridge_core::export_bridge!(&[]);
