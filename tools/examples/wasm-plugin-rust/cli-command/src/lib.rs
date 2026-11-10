use karandash_wasm_common::return_string;

karandash_wasm_common::wasm_base_exports!(
    "wasm-rust-cli-command-plugin",
    "WASM Rust CLI Command Plugin",
    "1.0.0",
    "CLI_COMMAND"
);

#[no_mangle]
pub extern "C" fn karandash_cli_commands() -> i32 {
    return_string(r#"[{"name":"wasm-rust-greet","help":"Greeting command","subcommands":[]}]"#)
}
