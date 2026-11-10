use karandash_wasm_common::return_string;

karandash_wasm_common::wasm_base_exports!(
    "wasm-rust-ai-token-allocator-plugin",
    "WASM Rust AI Token Allocator Plugin",
    "1.0.0",
    "AI_TOKEN_ALLOCATOR"
);

#[no_mangle]
pub extern "C" fn karandash_ai_allocate_tokens(
    _rules_ptr: i32, _rules_len: i32,
    _total: i32,
    _pri_ptr: i32, _pri_len: i32,
) -> i32 {
    return_string("{}")
}
