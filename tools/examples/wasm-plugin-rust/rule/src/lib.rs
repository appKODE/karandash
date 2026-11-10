use karandash_wasm_common::{read_host_string, return_string};

use karandash_plugin_common::models::{Finding, MergeRequest};

karandash_wasm_common::wasm_base_exports!(
    "wasm-rust-rule-plugin",
    "WASM Rust Rule Plugin",
    "1.0.0",
    "RULE"
);

const RULE_TITLE_LENGTH: &str = "wasm-title-length";
const RULE_DESCRIPTION_REQUIRED: &str = "wasm-description-required";

#[no_mangle]
pub extern "C" fn karandash_contributed_rules() -> i32 {
    let rules = format!(r#"["{RULE_TITLE_LENGTH}","{RULE_DESCRIPTION_REQUIRED}"]"#);
    return_string(&rules)
}

#[no_mangle]
pub extern "C" fn karandash_validate(
    rule_id_ptr: i32, rule_id_len: i32,
    mr_ptr: i32, mr_len: i32,
    _cfg_ptr: i32, _cfg_len: i32,
) -> i32 {
    let rule_id = read_host_string(rule_id_ptr, rule_id_len);
    let mr_json = read_host_string(mr_ptr, mr_len);
    let mr: MergeRequest = match serde_json::from_str(&mr_json) {
        Ok(v) => v,
        Err(_) => return return_string("[]"),
    };
    let findings = match rule_id.as_str() {
        r if r == RULE_TITLE_LENGTH => {
            if mr.title.len() < 10 {
                vec![Finding {
                    severity: "WARNING".to_string(),
                    message: format!("Title too short ({} chars, min 10)", mr.title.len()),
                    rule: RULE_TITLE_LENGTH.to_string(),
                    location: None,
                    explanation: None,
                    suggestion: None,
                }]
            } else {
                vec![]
            }
        }
        r if r == RULE_DESCRIPTION_REQUIRED => {
            let missing = match &mr.description {
                None => true,
                Some(d) => d.trim().is_empty(),
            };
            if missing {
                vec![Finding {
                    severity: "ERROR".to_string(),
                    message: "MR description is required".to_string(),
                    rule: RULE_DESCRIPTION_REQUIRED.to_string(),
                    location: None,
                    explanation: None,
                    suggestion: None,
                }]
            } else {
                vec![]
            }
        }
        _ => vec![],
    };
    let json = serde_json::to_string(&findings).unwrap_or_else(|_| "[]".to_string());
    return_string(&json)
}
