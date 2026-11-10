use karandash_wasm_common::return_string;

karandash_wasm_common::wasm_base_exports!(
    "wasm-rust-provider-plugin",
    "WASM Rust Provider Plugin",
    "1.0.0",
    "MERGE_REQUEST_PROVIDER"
);

#[no_mangle]
pub extern "C" fn karandash_fetch_merge_request(
    _project_ptr: i32, _project_len: i32,
    _mr_ptr: i32, _mr_len: i32,
) -> i32 {
    return_string(r#"{"id":"1","iid":1,"title":"WASM Rust stub MR","description":"stub","source_branch":"feat","target_branch":"main","author":{"username":"bot","name":"Bot","email":null},"state":"opened","draft":false,"labels":[],"commits":[],"changes":[],"approvals":[],"reviewers":[]}"#)
}
