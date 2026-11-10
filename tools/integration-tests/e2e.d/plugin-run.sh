# Plugin-run matrix: one rule plugin per guest language (JS, WASM, native C, JVM JAR), each loaded from its own
# KARANDASH_PLUGIN_DIR and run by `validate` on both runtimes, asserting the plugin's own finding text. The plugins
# are the canonical examples under tools/examples/ (no fixtures of their own): the JS example is copied as is, the
# WASM C and native C examples are built here (emcc/clang, cc), the JAR is the standalone-jvm-plugin build output
# (built by plugin-examples-test.sh; KARANDASH_EXAMPLE_JAR overrides it). The native host runs the JAR through the
# JAR bridge (KARANDASH_BRIDGE_JAR, default the :plugins:loader:jar-bridge:shadowJar output).
# A plugin that fails to load drops its rule, so the run exits 0 without the finding: that is a FAIL here, not a skip.
# The guest-timeout case is the JS example with a `while (true) {}` validate: KARANDASH_PLUGIN_CALL_TIMEOUT=2s
# interrupts it, the run finishes with a warning instead of hanging.

PR="$F/plugin-run"
PR_EX="$PROJECT_ROOT/tools/examples"
PR_JAR="${KARANDASH_EXAMPLE_JAR:-$PR_EX/standalone-jvm-plugin/build/libs/standalone-jvm-plugin-1.0.0.jar}"
PR_BRIDGE="${KARANDASH_BRIDGE_JAR:-$PROJECT_ROOT/plugins/loader/jar-bridge/build/libs/karandash-jar-bridge.jar}"
case "$(uname -s)" in Darwin) PR_LIB_EXT=dylib ;; *) PR_LIB_EXT=so ;; esac
mkdir -p "$PR/js" "$PR/js-hang" "$PR/wasm" "$PR/native" "$PR/jar"
# lower-case, unconventional and short: every example rule reports on it
python3 -c 'import json, sys; m = json.load(open(sys.argv[1])); m["title"] = "update stuff"; json.dump(m, open(sys.argv[2], "w"))' \
    "$FIXTURES/test-mr.json" "$PR/mr.json"
cp "$PR_EX/standalone-js-plugin/example-rule.mjs" "$PR/js/"
python3 -c 'import sys; h = "  validate(ruleId, mergeRequest, config) {\n"; print(open(sys.argv[1]).read().replace(h, h + "    while (true) {}\n"), end="")' \
    "$PR_EX/standalone-js-plugin/example-rule.mjs" >"$PR/js-hang/example-rule.mjs"
{ command -v emcc || command -v clang; } >/dev/null 2>&1 \
    && OUT_DIR="$PR/wasm" bash "$PR_EX/standalone-wasm-c-plugin/build.sh" >"$PR/wasm-build.log" 2>&1
command -v cc >/dev/null 2>&1 \
    && cc -std=gnu11 -shared -fPIC -O2 -I"$PROJECT_ROOT/docs/native-sdk" "$PR_EX/standalone-native-plugin/src/example-rule.c" \
        -o "$PR/native/libnative-example-rule.$PR_LIB_EXT" >"$PR/native-build.log" 2>&1
[[ -f "$PR_JAR" ]] && cp "$PR_JAR" "$PR/jar/"
printf 'rules:\n  js-title-length:\n    enabled: true\n    min-length: 20\n' >"$PR/js.yaml"
printf 'rules:\n  wasm-c-standalone-check:\n    enabled: true\n' >"$PR/wasm.yaml"
printf 'rules:\n  native-title-uppercase:\n    enabled: true\n' >"$PR/native.yaml"
printf 'rules:\n  exampleTitlePattern:\n    enabled: true\n' >"$PR/jar.yaml"

# plugin_run <id> <lang> <expected exit> <finding regex>: validate with only <lang>'s plugin directory
plugin_run() {
    SCEN_ENV=("KARANDASH_PLUGIN_DIR=$PR/$2" "KARANDASH_BRIDGE_JAR=$PR_BRIDGE")
    scenario "$1" "$3" "$4" -- validate --mr mr.json --config "$2.yaml" --no-auto-detect --no-cache
    SCEN_ENV=()
}

scenarios_plugin_run() {
    SCEN_DIR="$PR"
    plugin_run plugin-run-js js 1 'Title too short \(12 chars\)\. Minimum: 20 +js-title-length'
    if needs plugin-run-wasm "$PR/wasm/plugin.wasm"; then
        plugin_run plugin-run-wasm wasm 1 'Title must start with feat:.* +wasm-c-standalone-check'
    fi
    if needs plugin-run-native "$PR/native/libnative-example-rule.$PR_LIB_EXT"; then
        plugin_run plugin-run-native native 0 'Title should contain at least one uppercase letter +native-title-uppercase'
    fi
    if needs plugin-run-jar "$PR/jar/$(basename "$PR_JAR")" && { [[ "$RUNTIME" == jvm ]] || needs plugin-run-jar "$PR_BRIDGE"; }; then
        plugin_run plugin-run-jar jar 1 "MR title 'update stuff' does not match required pattern.* +exampleTitlePattern"
    fi
    local start=$SECONDS
    SCEN_ENV=("KARANDASH_PLUGIN_DIR=$PR/js-hang" "KARANDASH_PLUGIN_CALL_TIMEOUT=2s")
    scenario plugin-run-js-timeout 0 "JS plugin call 'validate' exceeded 2s and was interrupted" -- \
        validate --mr mr.json --config js.yaml --no-auto-detect --no-cache
    SCEN_ENV=()
    # the 2s call limit plus start-up (a JVM start is ~2s): a hang would run until the harness is killed
    if [[ -z "$ONLY" || plugin-run-js-timeout-bounded =~ $ONLY ]]; then
        if ((SECONDS - start < 10)); then record_pass plugin-run-js-timeout-bounded; else
            record_fail plugin-run-js-timeout-bounded "took $((SECONDS - start))s"
        fi
    fi
    SCEN_DIR=""
}
