use karandash_wasm_common::{read_host_string, return_string};

karandash_wasm_common::wasm_base_exports!(
    "wasm-rust-reporter-plugin",
    "WASM Rust Reporter Plugin",
    "1.0.0",
    "REPORTER"
);

#[no_mangle]
pub extern "C" fn karandash_format_id() -> i32 {
    return_string("wasm-rust-text")
}

#[no_mangle]
pub extern "C" fn karandash_report(
    findings_ptr: i32, findings_len: i32,
    passed: i32,
    _cfg_ptr: i32, _cfg_len: i32,
) -> i32 {
    let findings_json = read_host_string(findings_ptr, findings_len);
    let status = if passed != 0 { "PASSED" } else { "FAILED" };
    return_string(&format!("WASM Rust Report — {status}\nFindings: {findings_json}"))
}
