# Karandash Integration Tests

End-to-end checks for the CLI and the plugin workflow: scaffold, build, install, load and run plugins of
every type and language against the real `karandash` binaries.

## Running

Build the CLI first, from the repository root:

```bash
./gradlew :cli:shadowJar                          # cli/build/libs/karandash.jar
./gradlew :cli:linkReleaseExecutableMacosArm64    # or LinuxX64, cli/build/bin/<target>/releaseExecutable/cli.kexe
```

Run everything (CLI smoke test, then every script below):

```bash
tools/integration-tests/run-tests.sh
```

Or one script at a time, from the repository root:

| Script | What it checks |
|--------|----------------|
| `quick-verify.sh` | Setup sanity: JAR, wrapper, `plugin create` for every type |
| `plugin-create-test.sh` | `plugin create` and `generate` give identical projects for every `--type`; each builds, installs, is listed and runs (rule finding, reporter output, platform service in `doctor --plugins`) |
| `plugin-lifecycle-test.sh` | install, list, validate, duplicate install, remove and re-install of a generated rule plugin |
| `script-module-test.sh` | the `scripts:` block runs JavaScript in the sandbox and turns `karandash.fail()` into a finding |
| `native-plugin-test.sh` | C, Kotlin/Native, Rust and Go rule examples build and export the C ABI |
| `multi-language-plugin-test.sh` | every native, WASM and JVM example builds and exports the symbols for its module types |
| `plugin-examples-test.sh tools/examples` | every example in `tools/examples` builds and loads in the JVM and native CLI, the built-in rules run in file and live (mock GitLab) mode, and standalone plugins' rules run during `validate` |
| `feature-e2e.sh` | every scenario id in [`docs/development/feature-matrix.md`](../../docs/development/feature-matrix.md) on the JAR and the native binary against the mock GitLab server: exit code, key output and, in live mode, the writes the mock received. `E2E_ONLY=<regex>` runs a subset |

### Environment

All optional; the defaults are the in-repo build outputs.

| Variable | Meaning |
|----------|---------|
| `KARANDASH_JAR` | CLI fat JAR |
| `KARANDASH_NATIVE` | CLI native binary for the host |
| `TEST_MR`, `FIXTURES_DIR`, `MOCK_SERVER` | fixtures used by `plugin-examples-test.sh` (`fixtures/`, `mock-server/`) |
| `SKIP_SDK_PUBLISH=1` | the plugin SDK is already in mavenLocal (scaffold builds resolve it from there) |
| `KARANDASH_BRIDGE_JAR` | JAR bridge the native CLI uses for JAR plugins; `run-tests.sh` builds `:plugins:loader:jar-bridge:shadowJar` when unset |
| `PLUGIN_TYPES` | `plugin-create-test.sh`: the types to cover (default: all five) |
| `PLUGIN_LANG`, `SKIP_HEAVY=1` | `plugin-examples-test.sh`: one language only; skip Kotlin/Native and JVM builds |
| `LOG_DIR` | `plugin-examples-test.sh`: where build logs go (default `/tmp/karandash-plugin-test-logs`) |
| `CI=true` | a skipped check (missing toolchain, binary or fixture) counts as a failure |

Each script exits `0` only when nothing failed. Locally a missing toolchain (Go, Rust, `wasm32` target,
emscripten, CMake, Node, OpenSSL) is reported as SKIP; with `CI=true` it fails the run.

## Isolation

Plugins load only from `~/.karandash/plugins`, so every script runs the CLI with its own temporary home
(`HOME=…`, which both the JAR and the native binary read first; the JAR also gets `-Duser.home=…`) and its own working directory under
`mktemp -d`, removed on exit. Nothing is written to your real `~/.karandash` or to the repository.

## CI

`tools/ci/gate.sh` (the `gate` job in `.github/workflows/ci.yml`) builds both binaries and runs `run-tests.sh`
with `CI=true`.

## Related documentation

- [Plugin Development Guide](../../docs/plugins/creating-plugins.md)
- [Plugin Command Reference](../../docs/reference/cli/plugin.md)
- [Validate Command Reference](../../docs/reference/cli/validate.md)
