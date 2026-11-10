use std::ffi::CString;
use std::os::raw::c_char;

const MODULE_ID: &str = "rust-example-mutator";
const MODULE_NAME: &str = "Rust Example Mutator";
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
pub extern "C" fn karandash_add_labels(
    _project_id: *const c_char,
    _mr_id: *const c_char,
    _labels_json: *const c_char,
) -> i32 {
    1
}

#[no_mangle]
pub extern "C" fn karandash_remove_labels(
    _project_id: *const c_char,
    _mr_id: *const c_char,
    _labels_json: *const c_char,
) -> i32 {
    1
}

#[no_mangle]
pub extern "C" fn karandash_set_draft(
    _project_id: *const c_char,
    _mr_id: *const c_char,
    _draft: i32,
) -> i32 {
    1
}

#[no_mangle]
pub extern "C" fn karandash_update_title(
    _project_id: *const c_char,
    _mr_id: *const c_char,
    _title: *const c_char,
) -> i32 {
    1
}

#[no_mangle]
pub extern "C" fn karandash_update_description(
    _project_id: *const c_char,
    _mr_id: *const c_char,
    _description: *const c_char,
) -> i32 {
    1
}

#[no_mangle]
pub extern "C" fn karandash_free_string(ptr: *const c_char) {
    if !ptr.is_null() {
        unsafe {
            drop(CString::from_raw(ptr as *mut c_char));
        }
    }
}
