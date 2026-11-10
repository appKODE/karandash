use std::ffi::{CStr, CString};
use std::os::raw::c_char;

const MODULE_ID: &str = "rust-ai-token-allocator-example";
const MODULE_NAME: &str = "Rust AI Token Allocator Example Plugin";
const MODULE_VERSION: &str = "1.0.0";
const MODULE_TYPE: &str = "AI_TOKEN_ALLOCATOR";

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

/// Computes token budget allocation for an AI request.
/// Receives a JSON object with: model (string), contextTokens (int), config (object).
/// Returns a JSON object with: maxTokens (int), reservedTokens (int), availableTokens (int).
#[no_mangle]
pub extern "C" fn karandash_ai_allocate_tokens(request_json: *const c_char) -> *const c_char {
    let _req = unsafe { from_c_str(request_json) }.unwrap_or("{}");
    // Stub: return a fixed allocation demonstrating the ABI contract.
    to_c_string(r#"{"maxTokens":4096,"reservedTokens":512,"availableTokens":3584}"#)
}

#[no_mangle]
pub extern "C" fn karandash_free_string(ptr: *const c_char) {
    if !ptr.is_null() {
        unsafe {
            drop(CString::from_raw(ptr as *mut c_char));
        }
    }
}
