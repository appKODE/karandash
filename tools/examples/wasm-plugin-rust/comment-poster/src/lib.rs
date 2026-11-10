use karandash_wasm_common::return_string;

karandash_wasm_common::wasm_base_exports!(
    "wasm-rust-comment-poster-plugin",
    "WASM Rust Comment Poster Plugin",
    "1.0.0",
    "COMMENT_POSTER"
);

#[no_mangle]
pub extern "C" fn karandash_post_comment(
    _project_ptr: i32, _project_len: i32,
    _mr_ptr: i32, _mr_len: i32,
    _body_ptr: i32, _body_len: i32,
) -> i32 {
    return_string("1001")
}
