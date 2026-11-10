use std::ffi::CString;
use std::os::raw::c_char;

const MODULE_ID: &str = "rust-example-comment-poster";
const MODULE_NAME: &str = "Rust Example Comment Poster";
const MODULE_VERSION: &str = "1.0.0";
const MODULE_TYPE: &str = "PLATFORM_SERVICE";

fn to_c_string(s: &str) -> *const c_char {
    CString::new(s).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "C" fn karandash_abi_version() -> i32 {
    1
}

#[no_mangle]
pub extern "C" fn karandash_module_id() -> *const c_char {
    to_c_string(MODULE_ID)
}

#[no_mangle]
pub extern "C" fn karandash_module_name() -> *const c_char {
    to_c_string(MODULE_NAME)
}

#[no_mangle]
pub extern "C" fn karandash_module_version() -> *const c_char {
    to_c_string(MODULE_VERSION)
}

#[no_mangle]
pub extern "C" fn karandash_module_type() -> *const c_char {
    to_c_string(MODULE_TYPE)
}

#[no_mangle]
pub extern "C" fn karandash_initialize(_context_json: *const c_char) -> i32 {
    1
}

#[no_mangle]
pub extern "C" fn karandash_shutdown() {}

#[no_mangle]
pub extern "C" fn karandash_post_comment(
    _project_id: *const c_char,
    _mr_id: *const c_char,
    _body: *const c_char,
) -> *const c_char {
    to_c_string("comment-123")
}

#[no_mangle]
pub extern "C" fn karandash_update_comment(
    _project_id: *const c_char,
    _mr_id: *const c_char,
    _comment_id: *const c_char,
    _body: *const c_char,
) -> i32 {
    1
}

#[no_mangle]
pub extern "C" fn karandash_find_existing_comment(
    _project_id: *const c_char,
    _mr_id: *const c_char,
    _marker: *const c_char,
) -> *const c_char {
    std::ptr::null()
}

#[no_mangle]
pub extern "C" fn karandash_post_inline_comment(
    _project_id: *const c_char,
    _mr_id: *const c_char,
    _body: *const c_char,
    _file_path: *const c_char,
    _line: i32,
    _diff_refs_json: *const c_char,
) -> *const c_char {
    to_c_string("comment-124")
}

#[no_mangle]
pub extern "C" fn karandash_free_string(ptr: *const c_char) {
    if !ptr.is_null() {
        unsafe {
            drop(CString::from_raw(ptr as *mut c_char));
        }
    }
}
