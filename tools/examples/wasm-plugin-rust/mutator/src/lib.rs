karandash_wasm_common::wasm_base_exports!(
    "wasm-rust-mutator-plugin",
    "WASM Rust Mutator Plugin",
    "1.0.0",
    "MERGE_REQUEST_MUTATOR"
);

#[no_mangle]
pub extern "C" fn karandash_add_labels(
    _project_ptr: i32, _project_len: i32,
    _mr_ptr: i32, _mr_len: i32,
    _labels_ptr: i32, _labels_len: i32,
) -> i32 {
    1
}

#[no_mangle]
pub extern "C" fn karandash_remove_labels(
    _project_ptr: i32, _project_len: i32,
    _mr_ptr: i32, _mr_len: i32,
    _labels_ptr: i32, _labels_len: i32,
) -> i32 {
    1
}

#[no_mangle]
pub extern "C" fn karandash_set_draft(
    _project_ptr: i32, _project_len: i32,
    _mr_ptr: i32, _mr_len: i32,
    _draft: i32,
) -> i32 {
    1
}

#[no_mangle]
pub extern "C" fn karandash_mutator_update_title(
    _project_ptr: i32, _project_len: i32,
    _mr_ptr: i32, _mr_len: i32,
    _title_ptr: i32, _title_len: i32,
) -> i32 {
    1
}

#[no_mangle]
pub extern "C" fn karandash_mutator_update_description(
    _project_ptr: i32, _project_len: i32,
    _mr_ptr: i32, _mr_len: i32,
    _description_ptr: i32, _description_len: i32,
) -> i32 {
    1
}
