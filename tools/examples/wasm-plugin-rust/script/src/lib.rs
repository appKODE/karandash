use karandash_wasm_common::return_string;

karandash_wasm_common::wasm_base_exports!(
    "wasm-rust-script-plugin",
    "WASM Rust Script Plugin",
    "1.0.0",
    "SCRIPT"
);

/// Execute scripts against the provided merge request data.
///
/// All three parameters arrive as (ptr, len) pairs pointing into WASM linear
/// memory. Returns a length-prefixed JSON findings array.
///
/// This minimal implementation returns a single INFO finding to demonstrate
/// that the script execution entry point is wired correctly.
#[no_mangle]
pub extern "C" fn karandash_execute_scripts(
    _scripts_ptr: i32, _scripts_len: i32,
    _mr_ptr: i32, _mr_len: i32,
    _ctx_ptr: i32, _ctx_len: i32,
) -> i32 {
    return_string(
        r#"[{"rule":"wasm-rust-script","severity":"INFO","message":"script executed successfully","location":null}]"#,
    )
}
