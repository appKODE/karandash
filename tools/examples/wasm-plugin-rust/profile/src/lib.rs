use karandash_wasm_common::return_string;

karandash_wasm_common::wasm_base_exports!(
    "wasm-rust-profile-plugin",
    "WASM Rust Profile Plugin",
    "1.0.0",
    "PROFILE"
);

#[no_mangle]
pub extern "C" fn karandash_profile_id() -> i32 {
    return_string("wasm-rust-example")
}

#[no_mangle]
pub extern "C" fn karandash_profile_summary() -> i32 {
    return_string("Example WASM Rust profile")
}

#[no_mangle]
pub extern "C" fn karandash_profile_tags() -> i32 {
    return_string(r#"["example","wasm"]"#)
}

#[no_mangle]
pub extern "C" fn karandash_profile_rule_configs() -> i32 {
    return_string(r#"{"titleFormat":{"pattern":"^.*$"}}"#)
}

#[no_mangle]
pub extern "C" fn karandash_profile_reporter_configs() -> i32 {
    return_string("{}")
}
