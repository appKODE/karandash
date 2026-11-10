use karandash_wasm_common::return_string;

karandash_wasm_common::wasm_base_exports!(
    "wasm-rust-ai-provider-plugin",
    "WASM Rust AI Provider Plugin",
    "1.0.0",
    "AI_PROVIDER"
);

#[no_mangle]
pub extern "C" fn karandash_ai_complete(_ptr: i32, _len: i32) -> i32 {
    return_string(r#"{"content":"WASM Rust AI stub","model":"stub","inputTokens":10,"outputTokens":5}"#)
}
