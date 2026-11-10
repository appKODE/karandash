use std::ffi::{CStr, CString};
use std::os::raw::c_char;

mod models;
use models::{Finding, MergeRequest};

const MODULE_ID: &str = "rust-standalone-plugin";
const MODULE_NAME: &str = "Rust Standalone Rule Plugin";
const MODULE_VERSION: &str = "1.0.0";
const MODULE_TYPE: &str = "RULE";

const CONVENTIONAL_PREFIXES: &[&str] = &[
    "feat:", "fix:", "chore:", "refactor:", "docs:", "test:", "ci:",
];

fn to_c_string(s: &str) -> *const c_char {
    CString::new(s).unwrap_or_default().into_raw()
}

unsafe fn from_c_str<'a>(ptr: *const c_char) -> Option<&'a str> {
    if ptr.is_null() {
        return None;
    }
    CStr::from_ptr(ptr).to_str().ok()
}

fn has_conventional_prefix(title: &str) -> bool {
    CONVENTIONAL_PREFIXES.iter().any(|p| title.starts_with(p))
}

#[no_mangle]
pub extern "C" fn karandash_abi_version() -> i32 { 1 }

#[no_mangle]
pub extern "C" fn karandash_module_id() -> *const c_char { to_c_string(MODULE_ID) }

#[no_mangle]
pub extern "C" fn karandash_module_name() -> *const c_char { to_c_string(MODULE_NAME) }

#[no_mangle]
pub extern "C" fn karandash_module_version() -> *const c_char { to_c_string(MODULE_VERSION) }

#[no_mangle]
pub extern "C" fn karandash_module_type() -> *const c_char { to_c_string(MODULE_TYPE) }

#[no_mangle]
pub extern "C" fn karandash_initialize(_context_json: *const c_char) -> i32 { 1 }

#[no_mangle]
pub extern "C" fn karandash_shutdown() {}

#[no_mangle]
pub extern "C" fn karandash_contributed_rules() -> *const c_char {
    to_c_string(r#"["rust-title-check"]"#)
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
    if rule != "rust-title-check" {
        return to_c_string("[]");
    }

    let mr_str = match unsafe { from_c_str(mr_json) } {
        Some(s) => s,
        None => return to_c_string("[]"),
    };

    let mr: MergeRequest = match serde_json::from_str(mr_str) {
        Ok(v) => v,
        Err(_) => return to_c_string("[]"),
    };

    if !has_conventional_prefix(&mr.title) {
        let findings = vec![Finding {
            severity: "ERROR".to_string(),
            message: "Title must start with feat:, fix:, chore:, refactor:, docs:, test:, or ci:"
                .to_string(),
            rule: "rust-title-check".to_string(),
            location: None,
            explanation: None,
            suggestion: Some("Prefix the title with a conventional commit type, e.g. 'feat: ...'".to_string()),
        }];
        let json = serde_json::to_string(&findings).unwrap_or_else(|_| "[]".to_string());
        return to_c_string(&json);
    }

    to_c_string("[]")
}

#[no_mangle]
pub extern "C" fn karandash_format_id() -> *const c_char { to_c_string("") }

#[no_mangle]
pub extern "C" fn karandash_report(
    _findings_json: *const c_char,
    _passed: i32,
    _config_json: *const c_char,
) -> *const c_char {
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
