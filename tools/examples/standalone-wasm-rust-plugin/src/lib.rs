use std::alloc::{alloc, dealloc, Layout};

const MODULE_ID: &str = "wasm-rust-standalone-plugin";
const MODULE_NAME: &str = "WASM Rust Standalone Rule Plugin";
const MODULE_VERSION: &str = "1.0.0";
const MODULE_TYPE: &str = "RULE";
const RULE_ID: &str = "wasm-rust-standalone-check";

const CONVENTIONAL_PREFIXES: &[&str] = &[
    "feat:", "fix:", "chore:", "refactor:", "docs:", "test:", "ci:",
];

/* ── Memory management ───────────────────────────────────────────────────── */

#[no_mangle]
pub extern "C" fn karandash_alloc(size: i32) -> i32 {
    let layout = Layout::from_size_align(size as usize, 1).unwrap();
    unsafe { alloc(layout) as i32 }
}

#[no_mangle]
pub extern "C" fn karandash_dealloc(ptr: i32, len: i32) {
    let layout = Layout::from_size_align(len as usize, 1).unwrap();
    unsafe { dealloc(ptr as *mut u8, layout) }
}

/* ── String helpers ─────────────────────────────────────────────────────── */

fn return_string(s: &str) -> i32 {
    let bytes = s.as_bytes();
    let total = 4 + bytes.len();
    let layout = Layout::from_size_align(total, 1).unwrap();
    let ptr = unsafe { alloc(layout) };
    let len_bytes = (bytes.len() as u32).to_le_bytes();
    unsafe {
        std::ptr::copy_nonoverlapping(len_bytes.as_ptr(), ptr, 4);
        std::ptr::copy_nonoverlapping(bytes.as_ptr(), ptr.add(4), bytes.len());
    }
    ptr as i32
}

fn read_host_string(ptr: i32, len: i32) -> String {
    let slice = unsafe { std::slice::from_raw_parts(ptr as *const u8, len as usize) };
    String::from_utf8_lossy(slice).to_string()
}

/* ── ABI exports ────────────────────────────────────────────────────────── */

#[no_mangle]
pub extern "C" fn karandash_abi_version() -> i32 { 1 }

#[no_mangle]
pub extern "C" fn karandash_module_id() -> i32 { return_string(MODULE_ID) }

#[no_mangle]
pub extern "C" fn karandash_module_name() -> i32 { return_string(MODULE_NAME) }

#[no_mangle]
pub extern "C" fn karandash_module_version() -> i32 { return_string(MODULE_VERSION) }

#[no_mangle]
pub extern "C" fn karandash_module_type() -> i32 { return_string(MODULE_TYPE) }

/// ModuleDescriptor JSON: the module's types and the SDK properties it sets.
#[no_mangle]
pub extern "C" fn karandash_describe() -> i32 {
    return_string(&format!(
        "{{\"id\":\"{MODULE_ID}\",\"types\":[\"{MODULE_TYPE}\"],\"properties\":{{\"defaultEnabled\":true,\"supportedEnrichment\":[]}}}}"
    ))
}

#[no_mangle]
pub extern "C" fn karandash_initialize(_ptr: i32, _len: i32) -> i32 { 1 }

#[no_mangle]
pub extern "C" fn karandash_shutdown() -> i32 { 1 }

#[no_mangle]
pub extern "C" fn karandash_contributed_rules() -> i32 {
    return_string(&format!("[\"{RULE_ID}\"]"))
}

/* ── Validation ─────────────────────────────────────────────────────────── */

fn has_conventional_prefix(title: &str) -> bool {
    CONVENTIONAL_PREFIXES.iter().any(|p| title.starts_with(p))
}

/// Extract the "title" field value from a JSON string using simple byte scanning.
fn extract_title(json: &str) -> Option<String> {
    let key = "\"title\":";
    let kp = json.find(key)?;
    let rest = &json[kp + key.len()..];
    let rest = rest.trim_start();
    let rest = rest.strip_prefix('"')?;
    let end = rest.find('"')?;
    Some(rest[..end].to_string())
}

#[no_mangle]
pub extern "C" fn karandash_validate(
    rule_id_ptr: i32, rule_id_len: i32,
    mr_ptr: i32, mr_len: i32,
    _cfg_ptr: i32, _cfg_len: i32,
) -> i32 {
    let rule_id = read_host_string(rule_id_ptr, rule_id_len);
    if rule_id != RULE_ID {
        return return_string("[]");
    }

    let mr_json = read_host_string(mr_ptr, mr_len);
    let title = match extract_title(&mr_json) {
        Some(t) => t,
        None => {
            return return_string(
                &format!(
                    r#"[{{"rule":"{RULE_ID}","severity":"ERROR","message":"Could not read MR title","location":null}}]"#
                )
            );
        }
    };

    if !has_conventional_prefix(&title) {
        return return_string(
            &format!(
                r#"[{{"rule":"{RULE_ID}","severity":"ERROR","message":"Title must start with feat:, fix:, chore:, refactor:, docs:, test:, or ci:","location":null}}]"#
            )
        );
    }

    return_string("[]")
}
