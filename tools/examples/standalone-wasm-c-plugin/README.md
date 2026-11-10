# standalone-wasm-c-plugin

A minimal C WASM plugin for Karandash. One binary serves four module types, declared in
`karandash_describe`'s `types` (the host registers one part per type under the same id):

**Rule `wasm-c-standalone-check`** - MR title must start with a conventional commit prefix:
`feat:`, `fix:`, `chore:`, `refactor:`, `docs:`, `test:`, or `ci:`

**Reporter `wasm-c-standalone-report`** - one summary line (`reporters: { wasm-c-standalone-report: true }`)

**Profile `wasm-c-standalone-profile`** - enables the rule (`karandash profile list`)

**Command `wasm-c-standalone`** - listed in `karandash --help`

Uses the Karandash WASM ABI v1: strings are 4-byte LE length-prefixed, no WASI or libc required.

## Build

```bash
./build.sh
```

Output: `plugin.wasm`

## Requirements

- Emscripten (`emcc`) or LLVM/Clang with `wasm32` target support

## Usage

```yaml
plugins:
  - path: "./plugin.wasm"
```
