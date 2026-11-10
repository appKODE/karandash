use std::ffi::CString;
use std::os::raw::c_char;

const MODULE_ID: &str = "rust-example-provider";
const MODULE_NAME: &str = "Rust Example Provider";
const MODULE_VERSION: &str = "1.0.0";
const MODULE_TYPE: &str = "PLATFORM_SERVICE";

const MOCK_MR_JSON: &str = r#"{"title":"Mock MR from Rust","description":"Example","state":"opened","author":{"username":"mock"},"labels":[],"commits":[],"changedFiles":[],"comments":[],"approvals":[]}"#;

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
pub extern "C" fn karandash_fetch_merge_request(
    _project_id: *const c_char,
    _mr_id: *const c_char,
) -> *const c_char {
    to_c_string(MOCK_MR_JSON)
}

#[no_mangle]
pub extern "C" fn karandash_fetch_merge_request_by_url(
    _url: *const c_char,
) -> *const c_char {
    to_c_string(MOCK_MR_JSON)
}

#[no_mangle]
pub extern "C" fn karandash_free_string(ptr: *const c_char) {
    if !ptr.is_null() {
        unsafe {
            drop(CString::from_raw(ptr as *mut c_char));
        }
    }
}
