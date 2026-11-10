use std::ffi::{CStr, CString};
use std::os::raw::c_char;

const MODULE_ID: &str = "rust-profile-example";
const MODULE_NAME: &str = "Rust Profile Example Plugin";
const MODULE_VERSION: &str = "1.0.0";
const MODULE_TYPE: &str = "PROFILE";

fn to_c_string(s: &str) -> *const c_char {
    CString::new(s).unwrap_or_default().into_raw()
}

unsafe fn from_c_str<'a>(ptr: *const c_char) -> Option<&'a str> {
    if ptr.is_null() {
        return None;
    }
    CStr::from_ptr(ptr).to_str().ok()
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

/// Returns the unique profile identifier.
#[no_mangle]
pub extern "C" fn karandash_profile_id() -> *const c_char {
    to_c_string("rust-example-profile")
}

/// Returns a human-readable summary of what this profile enforces.
#[no_mangle]
pub extern "C" fn karandash_profile_summary() -> *const c_char {
    to_c_string("Example profile contributed by Rust plugin enforcing basic MR hygiene.")
}

/// Returns a JSON array of tag strings categorising this profile.
#[no_mangle]
pub extern "C" fn karandash_profile_tags() -> *const c_char {
    to_c_string(r#"["example","rust","hygiene"]"#)
}

/// Returns a JSON object mapping rule IDs to their config maps for this profile.
#[no_mangle]
pub extern "C" fn karandash_profile_rule_configs() -> *const c_char {
    to_c_string(r#"{"title-format":{"pattern":"^(feat|fix|chore):"}}"#)
}

/// Returns a JSON object mapping reporter IDs to their config maps for this profile.
#[no_mangle]
pub extern "C" fn karandash_profile_reporter_configs() -> *const c_char {
    to_c_string(r#"{"console":true}"#)
}

#[no_mangle]
pub extern "C" fn karandash_free_string(ptr: *const c_char) {
    if !ptr.is_null() {
        unsafe {
            drop(CString::from_raw(ptr as *mut c_char));
        }
    }
}
