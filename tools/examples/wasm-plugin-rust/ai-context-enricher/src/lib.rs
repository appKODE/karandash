use karandash_wasm_common::return_string;

karandash_wasm_common::wasm_base_exports!(
    "wasm-rust-ai-context-enricher-plugin",
    "WASM Rust AI Context Enricher Plugin",
    "1.0.0",
    "AI_CONTEXT_ENRICHER"
);

#[no_mangle]
pub extern "C" fn karandash_ai_enrich_context(
    _mr_ptr: i32, _mr_len: i32,
    _ctx_ptr: i32, _ctx_len: i32,
) -> i32 {
    return_string(r#"{"wasm_rust_enriched":"true"}"#)
}
