use karandash_wasm_common::{read_host_string, return_string};

karandash_wasm_common::wasm_base_exports!(
    "wasm-rust-ai-prompt-processor-plugin",
    "WASM Rust AI Prompt Processor Plugin",
    "1.0.0",
    "AI_PROMPT_PROCESSOR"
);

#[no_mangle]
pub extern "C" fn karandash_ai_process_prompt(
    ptr: i32, len: i32,
    _ctx_ptr: i32, _ctx_len: i32,
) -> i32 {
    let input = read_host_string(ptr, len);
    return_string(&input)
}

#[no_mangle]
pub extern "C" fn karandash_ai_process_system_prompt(
    ptr: i32, len: i32,
    _ctx_ptr: i32, _ctx_len: i32,
) -> i32 {
    let input = read_host_string(ptr, len);
    return_string(&input)
}
