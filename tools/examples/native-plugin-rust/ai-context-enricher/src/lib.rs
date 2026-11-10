use std::ffi::{CStr, CString};
use std::os::raw::c_char;

const MODULE_ID: &str = "rust-ai-context-enricher-example";
const MODULE_NAME: &str = "Rust AI Context Enricher Example Plugin";
const MODULE_VERSION: &str = "1.0.0";
const MODULE_TYPE: &str = "AI_CONTEXT_ENRICHER";

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

/// Enriches the AI context before it is passed to the provider.
/// Receives a JSON object representing the current AI context (mergeRequest, config, etc.).
/// Returns an enriched JSON context object with additional fields merged in.
#[no_mangle]
pub extern "C" fn karandash_ai_enrich_context(context_json: *const c_char) -> *const c_char {
    let _ctx = unsafe { from_c_str(context_json) }.unwrap_or("{}");
    // Stub: return the context with an extra "enrichedBy" field to demonstrate the ABI.
    to_c_string(r#"{"enrichedBy":"rust-ai-context-enricher-example","additionalContext":"No extra context added by stub."}"#)
}

#[no_mangle]
pub extern "C" fn karandash_free_string(ptr: *const c_char) {
    if !ptr.is_null() {
        unsafe {
            drop(CString::from_raw(ptr as *mut c_char));
        }
    }
}
