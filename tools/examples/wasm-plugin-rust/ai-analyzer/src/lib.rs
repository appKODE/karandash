use karandash_wasm_common::return_string;

karandash_wasm_common::wasm_base_exports!(
    "wasm-rust-ai-analyzer-plugin",
    "WASM Rust AI Analyzer Plugin",
    "1.0.0",
    "AI_ANALYZER"
);

/// Analyze a merge request. Return either:
/// - A JSON array of Finding objects (final result)
/// - A JSON object with "aiRequest" key to request an AI completion:
///   `{"aiRequest": {"prompt": "...", "model": "...", ...}}`
///   The host calls aiService.complete() and passes the response to
///   karandash_ai_analyze_continue.
#[no_mangle]
pub extern "C" fn karandash_ai_analyze(
    _mr_ptr: i32, _mr_len: i32,
    _budget_ptr: i32, _budget_len: i32,
    _model_ptr: i32, _model_len: i32,
    _cfg_ptr: i32, _cfg_len: i32,
) -> i32 {
    // Stub: return empty findings directly (no AI call needed).
    return_string("[]")
}

/// Receive AI completion response and return final findings or another
/// aiRequest for multi-turn analysis.
///
/// response_ptr/response_len: JSON with {content, promptTokens, completionTokens, totalTokens, model}
#[no_mangle]
pub extern "C" fn karandash_ai_analyze_continue(
    _response_ptr: i32, _response_len: i32,
) -> i32 {
    // Stub: return empty findings.
    return_string("[]")
}
