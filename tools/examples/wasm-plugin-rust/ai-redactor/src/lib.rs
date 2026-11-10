use karandash_wasm_common::{read_host_string, return_string};

karandash_wasm_common::wasm_base_exports!(
    "wasm-rust-ai-redactor-plugin",
    "WASM Rust AI Redactor Plugin",
    "1.0.0",
    "AI_REDACTOR"
);

#[no_mangle]
pub extern "C" fn karandash_ai_redact(
    text_ptr: i32, text_len: i32,
    _ctx_ptr: i32, _ctx_len: i32,
) -> i32 {
    let input = read_host_string(text_ptr, text_len);
    return_string(&input)
}
