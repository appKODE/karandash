use std::ffi::{CStr, CString};
use std::os::raw::c_char;

const MODULE_ID: &str = "rust-ai-redactor-example";
const MODULE_NAME: &str = "Rust AI Redactor Example Plugin";
const MODULE_VERSION: &str = "1.0.0";
const MODULE_TYPE: &str = "AI_REDACTOR";

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

/// Redacts sensitive content from text before it is sent to an AI provider.
/// Receives the raw text string to redact.
/// Returns the redacted text string with sensitive patterns replaced.
#[no_mangle]
pub extern "C" fn karandash_ai_redact(text: *const c_char) -> *const c_char {
    let input = unsafe { from_c_str(text) }.unwrap_or("");
    // Stub: replace any token-like strings (Bearer ...) with [REDACTED].
    // A real implementation would apply comprehensive secret-detection patterns.
    let redacted = input.replace("Bearer ", "Bearer [REDACTED] ");
    to_c_string(&redacted)
}

#[no_mangle]
pub extern "C" fn karandash_free_string(ptr: *const c_char) {
    if !ptr.is_null() {
        unsafe {
            drop(CString::from_raw(ptr as *mut c_char));
        }
    }
}
