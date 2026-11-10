use std::ffi::{CStr, CString};
use std::os::raw::c_char;

const MODULE_ID: &str = "rust-config-format-example";
const MODULE_NAME: &str = "Rust Config Format Example Plugin";
const MODULE_VERSION: &str = "1.0.0";
const MODULE_TYPE: &str = "CONFIG_FORMAT";

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

/// Returns the unique format identifier (e.g. "toml").
#[no_mangle]
pub extern "C" fn karandash_config_format_id() -> *const c_char {
    to_c_string("rust-example-toml")
}

/// Returns a JSON array of file extensions handled by this format (e.g. [".toml"]).
#[no_mangle]
pub extern "C" fn karandash_config_format_extensions() -> *const c_char {
    to_c_string(r#"[".toml"]"#)
}

/// Parses config content into a JSON object representing the config map.
/// Receives the raw file content as a string; returns a JSON config map or error JSON.
#[no_mangle]
pub extern "C" fn karandash_config_parse(content: *const c_char) -> *const c_char {
    let _text = unsafe { from_c_str(content) }.unwrap_or("");
    // Minimal stub: return an empty config map to demonstrate the ABI.
    to_c_string(r#"{"rules":{},"reporters":{}}"#)
}

/// Resolves the extends path from the parsed config map JSON.
/// Returns the extends value as a plain string, or empty string if not present.
#[no_mangle]
pub extern "C" fn karandash_config_parse_extends(config_json: *const c_char) -> *const c_char {
    let _json = unsafe { from_c_str(config_json) }.unwrap_or("{}");
    to_c_string("")
}

#[no_mangle]
pub extern "C" fn karandash_free_string(ptr: *const c_char) {
    if !ptr.is_null() {
        unsafe {
            drop(CString::from_raw(ptr as *mut c_char));
        }
    }
}
