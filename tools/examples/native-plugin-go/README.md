# Go Plugin Example

A complete, self-contained example of a Karandash rule plugin written in Go using the native plugin ABI.

## What This Plugin Does

Provides one validation rule:

1. **go-title-length**: Checks that MR title is at least 10 characters long

**Example validations:**
- OK: Title: "Add new feature" (15 chars, valid)
- No: Title: "Add auth" (8 chars, too short)

## Prerequisites

- Go 1.21 or later
- CGO enabled (default on most platforms)
- C toolchain (GCC on Linux, Clang on macOS)
- No additional tools needed

On macOS, install Xcode Command Line Tools:
```bash
xcode-select --install
```

On Linux, install build tools:
```bash
# Ubuntu/Debian
sudo apt-get install build-essential

# CentOS/RHEL
sudo yum groupinstall "Development Tools"
```

## Building

### Option 1: Using Makefile (Recommended)

```bash
# macOS ARM64 (default)
make macos-arm64

# macOS x64
make macos-x64

# Linux x64
make linux-x64
```

This produces:
- **macOS**: `libgo-example.dylib`
- **Linux**: `libgo-example.so`
- **Header file**: `libgo-example.h` (generated, can be ignored)

### Option 2: Manual Build

**macOS ARM64:**
```bash
CGO_ENABLED=1 GOOS=darwin GOARCH=arm64 go build -buildmode=c-shared -o libgo-example.dylib .
```

**macOS x64:**
```bash
CGO_ENABLED=1 GOOS=darwin GOARCH=amd64 go build -buildmode=c-shared -o libgo-example.dylib .
```

**Linux x64:**
```bash
CGO_ENABLED=1 GOOS=linux GOARCH=amd64 go build -buildmode=c-shared -o libgo-example.so .
```

## Installation

### Option 1: Using Karandash CLI

```bash
karandash plugin install libgo-example.dylib
```

### Option 2: Manual Installation

```bash
mkdir -p ~/.karandash/plugins
cp libgo-example.dylib ~/.karandash/plugins/
# Or .so on Linux
```

### Verify Installation

```bash
karandash plugin list
```

You should see "Go Example Plugin" in the list.

## Configuration

Add the rule to your `karandash.config.kts`:

```kotlin
rules {
    rule("go-title-length") {
        severity = warning
    }
}
```

This rule doesn't require additional configuration in this example.

## Project Structure

```
native-plugin-go/
├── Makefile               # Build automation
├── main.go                # Main plugin implementation
├── models.go              # Data structures for JSON
├── libgo-example.h        # Generated header (can ignore)
├── libgo-example.dylib    # Generated shared library
├── composite/main.go      # One library serving RULE + REPORTER + ENRICHMENT_PROVIDER (karandash_describe)
├── <type>/main.go         # One single-type example per module type (reporter, provider, ai_analyzer, ...)
└── README.md              # This file
```

## How It Works

### 1. Native Plugin ABI

Go uses CGO (C Go) to export functions as C functions. The `//export` directive immediately before a function exports it with C calling convention:

```go
//export karandash_module_name
func karandash_module_name() *C.char {
    return toCString(moduleName)
}
```

The `//export` comment must be directly above the function (no blank lines).

### 2. Memory Management

All strings returned by the plugin must be heap-allocated using C's malloc (via `C.CString()`). Go's CGO handles the allocation:

```go
func toCString(s string) *C.char {
    return C.CString(s)  // Allocates via C malloc
}

//export karandash_free_string
func karandash_free_string(ptr *C.char) {
    if ptr != nil {
        C.free(unsafe.Pointer(ptr))
    }
}
```

The host calls `karandash_free_string()` to free all returned strings.

### 3. JSON Communication

Complex data is passed as JSON strings. The plugin:
- Receives **MR data** as JSON (title, description, branches, commits, files)
- Receives **configuration** as JSON (rule config from karandash.config.kts)
- Returns **findings** as JSON array

The example uses Go's built-in `encoding/json` package:

```go
var mr MergeRequest
if err := json.Unmarshal([]byte(mrStr), &mr); err != nil {
    return toCString("[]")
}
```

### 4. CGO C/Go Interop

Go code can call C functions and use C types via the `C` package:

```go
import "C"

// Call C malloc
ptr := C.malloc(C.size_t(size))

// Call C free
C.free(ptr)

// Use C types
var count C.int = 42
var str *C.char = C.CString("hello")
```

The `import "C"` must be preceded by a comment block with C code/includes.

## JSON Schema

The plugin receives MR data in this JSON format:

```json
{
  "id": "12345",
  "iid": 1,
  "title": "Fix parser bug",
  "description": "Fixes issue with...",
  "source_branch": "bugfix/parser",
  "target_branch": "main",
  "author": {
    "username": "jdoe",
    "name": "John Doe",
    "email": "jdoe@example.com"
  },
  "state": "opened",
  "draft": false,
  "labels": ["bug"],
  "commits": [
    {
      "sha": "abc123",
      "message": "Fix parser",
      "author": {
        "name": "John Doe",
        "email": "jdoe@example.com"
      },
      "timestamp": "2024-01-15T10:30:00Z"
    }
  ],
  "changes": [
    {
      "path": "src/parser.go",
      "changeType": "MODIFIED",
      "additions": 10,
      "deletions": 5
    }
  ],
  "approvals": [
    {
      "user": {
        "username": "reviewer",
        "name": "Jane Reviewer",
        "email": "jane@example.com"
      },
      "approved_at": "2024-01-15T10:30:00Z"
    }
  ]
}
```

**Critical field names:**
- OK: `source_branch` NOT `sourceBranch`
- OK: `target_branch` NOT `targetBranch`
- OK: `approved_at` NOT `approvedAt`

The plugin returns findings as a JSON array:

```json
[
  {
    "rule": "go-title-length",
    "severity": "WARNING",
    "message": "Title is too short (8 chars, minimum 10)",
    "location": null,
    "explanation": null
  }
]
```

## Customizing This Plugin

To create your own Go rule plugin:

### 1. Copy the Directory

```bash
cp -r native-plugin-go my-go-plugin
cd my-go-plugin
```

### 2. Update Module Metadata

Edit `main.go`:

```go
const (
    moduleID      = "my-custom-plugin"
    moduleName    = "My Custom Plugin"
    moduleVersion = "1.0.0"
    moduleType    = "RULE"
)
```

### 3. Update Data Models

Edit `models.go` if you need to parse additional MR fields.

### 4. Implement Your Rules

Update `karandash_contributed_rules()`:

```go
//export karandash_contributed_rules
func karandash_contributed_rules() *C.char {
    rules := []string{"my-rule-1", "my-rule-2"}
    data, _ := json.Marshal(rules)
    return toCString(string(data))
}
```

Implement validation in `karandash_validate()`:

```go
//export karandash_validate
func karandash_validate(ruleID *C.char, mrJSON *C.char, configJSON *C.char) *C.char {
    rule := C.GoString(ruleID)
    if rule != "my-rule-1" {
        return toCString("[]")
    }

    mrStr := C.GoString(mrJSON)
    var mr MergeRequest
    if err := json.Unmarshal([]byte(mrStr), &mr); err != nil {
        return toCString("[]")
    }

    var findings []Finding

    // Your validation logic here
    if someViolation {
        findings = append(findings, Finding{
            Rule:     "my-rule-1",
            Severity: "ERROR",
            Message:  "Validation failed",
        })
    }

    data, _ := json.Marshal(findings)
    return toCString(string(data))
}
```

### 5. Build and Test

```bash
make macos-arm64
```

### 6. Install

```bash
mkdir -p ~/.karandash/plugins
cp libgo-example.dylib ~/.karandash/plugins/
```

## Go-Specific Notes

### Large Binary Size

Go binaries include the entire Go runtime (~5-10MB). This is because:
- Go runtime is statically linked
- No dynamic runtime dependency
- Includes garbage collector and memory management

### Package Main Required

Go shared libraries built with `-buildmode=c-shared` must use `package main` and include an empty `main()` function:

```go
package main

func main() {}
```

This is required even though the function is never called.

### Export Comments Matter

The `//export` comment must be:
- Immediately above the function (no blank lines)
- Directly before the function declaration
- In the same file as the function

Incorrect:
```go
//export myFunc

func myFunc() {}  // Error: //export not directly above
```

Correct:
```go
//export myFunc
func myFunc() {}
```

### Generated Header File

Go generates a `.h` header file with C declarations. You can ignore it; it's automatically generated:

```bash
libgo-example.h  # Auto-generated, not needed for plugin
```

### JSON Struct Tags

Go's JSON parsing uses struct tags to map JSON field names to Go struct fields:

```go
type MergeRequest struct {
    ID           string       `json:"id"`
    Title        string       `json:"title"`
    SourceBranch string       `json:"source_branch"`  // Maps JSON "source_branch" to Go field
    TargetBranch string       `json:"target_branch"`
    Author       User         `json:"author"`
    Changes      []FileChange `json:"changes"`
    Approvals    []Approval   `json:"approvals"`
}
```

### CGO Type Conversions

Common CGO type conversions:
- `C.GoString(ptr)` = Convert C string to Go string (safe, reads until null terminator)
- `C.CString(s)` = Convert Go string to C string (allocates, must free)
- `unsafe.Pointer()` = Convert to/from C pointers
- `C.int` = `int32`
- `C.uint` = `uint32`
- `C.char` = `byte` or `uint8`

## Testing

### Manual Testing

Create a test file `test_main.go`:

```go
package main

import (
    "fmt"
    "unsafe"
)

func testPlugin() {
    // Initialize
    result := karandash_initialize(nil)
    fmt.Printf("Initialized: %v\n", result == 1)

    // Get module info
    namePtr := karandash_module_name()
    name := C.GoString(namePtr)
    fmt.Printf("Plugin: %s\n", name)
    karandash_free_string(namePtr)

    // Test validation
    testMr := `{"title":"test","source_branch":"feature/test"}`
    findings := karandash_validate(
        C.CString("go-title-length"),
        C.CString(testMr),
        C.CString("{}"),
    )
    fmt.Printf("Findings: %s\n", C.GoString(findings))
    karandash_free_string(findings)

    // Shutdown
    karandash_shutdown()
}
```

Run tests with:
```bash
go test ./...
```

### Integration Testing

Test with actual Karandash:

```bash
# Build plugin
make macos-arm64

# Install to test location
mkdir -p ~/.karandash/plugins-test
cp libgo-example.dylib ~/.karandash/plugins-test/

# Test with sample MR
karandash validate --config test.kts --mr sample-mr.json
```

## Troubleshooting

**"cgo: C compiler not found"**
- macOS: Install Xcode Command Line Tools: `xcode-select --install`
- Linux: Install build tools: `sudo apt-get install build-essential`
- Windows: Use MinGW or MSVC

**"executable file not found in $PATH: gcc"**
- Ensure C compiler is installed and in PATH
- On macOS: `xcode-select --install`
- On Linux: `sudo apt-get install gcc`

**Binary is very large**
- This is expected (~5-10MB). Go embeds the entire runtime.
- Use `strip` to reduce size: `strip libgo-example.dylib`
- Even stripped, it will be larger than C/Rust equivalents

**Plugin won't load at runtime**
- Verify binary format: `file libgo-example.dylib`
- Should show "Mach-O 64-bit dynamically linked shared library"
- Check that `//export` is directly above function name (no blank lines)

**"Symbol not found: _karandash_module_name"**
- Ensure function is marked with `//export` immediately above declaration
- Verify function is in `package main`
- Check that `package main` and `func main() {}` exist

**"undefined reference to 'malloc' or 'free'"**
- Ensure C header is included: `import "C"`
- This usually works automatically; check CGO settings

**JSON unmarshaling fails**
- Add `json:"-"` to fields you want to ignore
- Use pointers for optional fields: `*string` instead of `string`
- Check that JSON field names match struct tags exactly (case-sensitive)

**Memory leaks**
- Ensure all returned strings are freed by the host
- Check that `karandash_free_string()` is called for every string returned
- Use `valgrind` to detect leaks: `valgrind ./test`

## Performance Tips

- Go compiles to native machine code with good optimization
- Use `go build -ldflags="-s -w"` to reduce binary size
- Avoid allocating large objects repeatedly in validation loop
- Cache parsed configuration between validations
- Use `strings.Builder` for efficient string building
- Profile with `pprof` if needed: `go tool pprof`

## Advanced: Adding Dependencies

Go dependencies are managed with `go mod`. Add a dependency:

```bash
go get github.com/some/package
```

Then use in code:

```go
import "github.com/some/package"

result := package.Function()
```

The dependency will be linked statically into the shared library.

## Security Considerations

- Validate all input strings (check for NULL, validate length)
- Use `C.GoString()` to safely convert C strings (it reads until null terminator)
- Don't trust JSON content (could be malicious)
- Limit memory allocations (prevent DoS)
- Don't access filesystem or network without explicit permission
- Sanitize error messages (don't leak sensitive info)
- Be careful with `unsafe` pointers (they bypass Go's safety)

## Language-Specific Resources

- [Go Documentation](https://golang.org/doc/)
- [CGO Documentation](https://pkg.go.dev/cmd/cgo)
- [Go Blog: cgo and Go](https://golang.org/blog/cgo)
- [JSON in Go](https://golang.org/pkg/encoding/json/)

## License

This example is public domain. Copy and modify freely for your own plugins.
