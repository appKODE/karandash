use karandash_wasm_common::{read_host_string, return_string};

karandash_wasm_common::wasm_base_exports!(
    "wasm-rust-ai-response-transformer-plugin",
    "WASM Rust AI Response Transformer Plugin",
    "1.0.0",
    "AI_RESPONSE_TRANSFORMER"
);

#[no_mangle]
pub extern "C" fn karandash_ai_transform_response(
    resp_ptr: i32, resp_len: i32,
    _req_ptr: i32, _req_len: i32,
    _ctx_ptr: i32, _ctx_len: i32,
) -> i32 {
    let input = read_host_string(resp_ptr, resp_len);
    return_string(&input)
}
