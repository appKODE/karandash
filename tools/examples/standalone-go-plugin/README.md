# standalone-go-plugin

A minimal Go native plugin for Karandash that implements a single rule:

**Rule `go-title-check`** - MR title must start with a conventional commit prefix:
`feat:`, `fix:`, `chore:`, `refactor:`, `docs:`, `test:`, or `ci:`

## Build

```bash
make
```

Output: `libgo-standalone.dylib` (macOS) or `libgo-standalone.so` (Linux)

## Requirements

- Go 1.21+ with CGO enabled
- C compiler (for CGO)

## Usage

```yaml
plugins:
  - path: "./libgo-standalone.dylib"
```
