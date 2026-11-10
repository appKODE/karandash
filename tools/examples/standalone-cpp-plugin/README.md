# standalone-cpp-plugin

A minimal C++ native plugin for Karandash that implements a single rule:

**Rule `cpp-title-check`** - MR title must start with a conventional commit prefix:
`feat:`, `fix:`, `chore:`, `refactor:`, `docs:`, `test:`, or `ci:`

## Build

```bash
make
```

Output: `libcpp-standalone.dylib` (macOS) or `libcpp-standalone.so` (Linux)

## Requirements

- C++17 compiler (`c++` / `clang++` / `g++`)

## Usage

```yaml
plugins:
  - path: "./libcpp-standalone.dylib"
```
