# standalone-python-c-generator

A minimal Python-generated native plugin for Karandash that implements a single rule:

**Rule `python-title-check`** - MR title must start with a conventional commit prefix:
`feat:`, `fix:`, `chore:`, `refactor:`, `docs:`, `test:`, or `ci:`

The Python script generates a C source file (`generated_plugin.c`) which is then
compiled into a shared library. No Python runtime is required at load time.

## Build

```bash
./build.sh
```

Output: `libpython-standalone.dylib` (macOS) or `libpython-standalone.so` (Linux)

## Requirements

- Python 3.6+
- GCC or Clang

## Usage

```yaml
plugins:
  - path: "./libpython-standalone.dylib"
```
