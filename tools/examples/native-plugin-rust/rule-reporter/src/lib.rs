use std::ffi::{CStr, CString};
use std::os::raw::c_char;

use karandash_plugin_common::models::{Finding, MergeRequest};

const MODULE_ID: &str = "rust-example-plugin";
const MODULE_NAME: &str = "Rust Example Plugin";
const MODULE_VERSION: &str = "1.0.0";
const MODULE_TYPE: &str = "RULE";

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

#[no_mangle]
pub extern "C" fn karandash_contributed_rules() -> *const c_char {
    to_c_string(r#"["rust-title-length"]"#)
}

#[no_mangle]
pub extern "C" fn karandash_validate(
    rule_id: *const c_char,
    mr_json: *const c_char,
    _config_json: *const c_char,
) -> *const c_char {
    let rule = match unsafe { from_c_str(rule_id) } {
        Some(s) => s,
        None => return to_c_string("[]"),
    };

    if rule != "rust-title-length" {
        return to_c_string("[]");
    }

    let mr_str = match unsafe { from_c_str(mr_json) } {
        Some(s) => s,
        None => return to_c_string("[]"),
    };

    let mr: MergeRequest = match serde_json::from_str(mr_str) {
        Ok(mr) => mr,
        Err(_) => return to_c_string("[]"),
    };

    let mut findings = Vec::new();
    if mr.title.len() < 10 {
        findings.push(Finding {
            severity: "WARNING".to_string(),
            message: format!("Title is too short ({} chars, minimum 10)", mr.title.len()),
            rule: "rust-title-length".to_string(),
            location: None,
            explanation: None,
            suggestion: None,
        });
    }

    let json = serde_json::to_string(&findings).unwrap_or_else(|_| "[]".to_string());
    to_c_string(&json)
}

#[no_mangle]
pub extern "C" fn karandash_format_id() -> *const c_char {
    to_c_string("rust-markdown")
}

#[no_mangle]
pub extern "C" fn karandash_report(
    _findings_json: *const c_char,
    passed: i32,
    _config_json: *const c_char,
) -> *const c_char {
    let status = if passed != 0 { "PASSED" } else { "FAILED" };
    to_c_string(&format!("# Karandash Report\n\n**Status:** {}\n", status))
}

#[no_mangle]
pub extern "C" fn karandash_free_string(ptr: *const c_char) {
    if !ptr.is_null() {
        unsafe {
            drop(CString::from_raw(ptr as *mut c_char));
        }
    }
}
