use karandash_wasm_common::return_string;

karandash_wasm_common::wasm_base_exports!(
    "wasm-rust-ai-interceptor-plugin",
    "WASM Rust AI Interceptor Plugin",
    "1.0.0",
    "AI_INTERCEPTOR"
);

#[no_mangle]
pub extern "C" fn karandash_ai_intercept(
    _req_ptr: i32, _req_len: i32,
    _ctx_ptr: i32, _ctx_len: i32,
) -> i32 {
    return_string(r#"{"passthrough":true}"#)
}
