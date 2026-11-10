use karandash_wasm_common::return_string;

karandash_wasm_common::wasm_base_exports!(
    "wasm-rust-config-format-plugin",
    "WASM Rust Config Format Plugin",
    "1.0.0",
    "CONFIG_FORMAT"
);

#[no_mangle]
pub extern "C" fn karandash_config_format_id() -> i32 {
    return_string("example-wasm-rust")
}

#[no_mangle]
pub extern "C" fn karandash_config_format_extensions() -> i32 {
    return_string(r#"["exr"]"#)
}

#[no_mangle]
pub extern "C" fn karandash_config_format_parse(_ptr: i32, _len: i32) -> i32 {
    return_string("{}")
}

#[no_mangle]
pub extern "C" fn karandash_config_format_parse_extends(_ptr: i32, _len: i32) -> i32 {
    return_string("null")
}
