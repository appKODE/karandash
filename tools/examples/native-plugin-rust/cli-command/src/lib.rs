use std::ffi::{CStr, CString};
use std::os::raw::c_char;

const MODULE_ID: &str = "rust-cli-command-example";
const MODULE_NAME: &str = "Rust CLI Command Example Plugin";
const MODULE_VERSION: &str = "1.0.0";
const MODULE_TYPE: &str = "CLI_COMMAND";

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

/// Returns a JSON array of CLI command specs contributed by this plugin.
/// Each spec has: name, description, args (array of {name, description, required}).
#[no_mangle]
pub extern "C" fn karandash_cli_commands() -> *const c_char {
    let commands = r#"[
  {
    "name": "rust-example",
    "description": "An example CLI command contributed by the Rust plugin",
    "args": [
      {"name": "input", "description": "Input value to process", "required": true}
    ]
  }
]"#;
    to_c_string(commands)
}

/// Executes the CLI command identified by name with the provided args JSON.
/// Returns a JSON object with: exitCode (int), output (string).
#[no_mangle]
pub extern "C" fn karandash_cli_execute(
    command_name: *const c_char,
    args_json: *const c_char,
) -> *const c_char {
    let name = unsafe { from_c_str(command_name) }.unwrap_or("");
    let _args = unsafe { from_c_str(args_json) }.unwrap_or("{}");

    if name != "rust-example" {
        return to_c_string(r#"{"exitCode":1,"output":"Unknown command"}"#);
    }

    to_c_string(r#"{"exitCode":0,"output":"Hello from Rust CLI command plugin!"}"#)
}

#[no_mangle]
pub extern "C" fn karandash_free_string(ptr: *const c_char) {
    if !ptr.is_null() {
        unsafe {
            drop(CString::from_raw(ptr as *mut c_char));
        }
    }
}
