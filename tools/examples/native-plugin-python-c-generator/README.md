# Python-to-C Generator Example (native plugin)

A Python script that GENERATES C source for a Karandash native plugin; the loaded plugin is C, not Python (no Python runtime is loaded).

## How It Works

1. **`plugin_logic.py`** defines rules as Python data structures
2. **`generate_plugin.py`** reads those definitions and generates a C source file
3. The C source is compiled to `.dylib`/`.so` implementing the Karandash native ABI v1

This approach lets you write rule definitions in Python while producing a native binary that Karandash can load without a Python runtime.

## Rules

| Rule ID | Description |
|---------|-------------|
| `python-title-length` | Title must be at least 10 characters |
| `python-labels-required` | MR must have at least one label |

## Build

```bash
# Requires: python3, cc (gcc/clang)
chmod +x build.sh
./build.sh
```

## Install

```bash
cp libpython-example.dylib ~/.karandash/plugins/   # macOS
cp libpython-example.so ~/.karandash/plugins/       # Linux
```
