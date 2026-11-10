use std::ffi::{CStr, CString};
use std::os::raw::c_char;

const MODULE_ID: &str = "rust-ai-prompt-processor-example";
const MODULE_NAME: &str = "Rust AI Prompt Processor Example Plugin";
const MODULE_VERSION: &str = "1.0.0";
const MODULE_TYPE: &str = "AI_PROMPT_PROCESSOR";

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

/// Transforms the user prompt before it is sent to the AI provider.
/// Receives a JSON object with: prompt (string), mergeRequest (object), config (object).
/// Returns the processed prompt string.
#[no_mangle]
pub extern "C" fn karandash_ai_process_prompt(context_json: *const c_char) -> *const c_char {
    let ctx = unsafe { from_c_str(context_json) }.unwrap_or("{}");
    // Stub: extract prompt from context and return it unchanged.
    // A real implementation would parse ctx and transform the prompt field.
    let _ = ctx;
    to_c_string("Please review this merge request and provide structured feedback.")
}

/// Transforms the system prompt before it is sent to the AI provider.
/// Receives a JSON object with: systemPrompt (string), config (object).
/// Returns the processed system prompt string.
#[no_mangle]
pub extern "C" fn karandash_ai_process_system_prompt(context_json: *const c_char) -> *const c_char {
    let _ctx = unsafe { from_c_str(context_json) }.unwrap_or("{}");
    to_c_string("You are a code review assistant. Provide concise, actionable feedback.")
}

#[no_mangle]
pub extern "C" fn karandash_free_string(ptr: *const c_char) {
    if !ptr.is_null() {
        unsafe {
            drop(CString::from_raw(ptr as *mut c_char));
        }
    }
}
