# Native C++ Plugin Example

Two Karandash plugins written in C++17 using the native plugin ABI v1:

- **cpp-rule-example** (RULE): two validation rules
- **cpp-reporter-example** (REPORTER): Markdown report generator

## Rules

| Rule ID | Checks | Severity |
|---------|--------|----------|
| `cpp-title-case` | MR title must start with an uppercase letter | WARNING |
| `cpp-max-files` | MR must not touch more than N changed files | ERROR |

`cpp-max-files` reads `maxFiles` from config (default: 20).

## Prerequisites

- C++17 compiler (Clang, GCC, or MSVC)
- CMake 3.14+ **or** GNU Make

## Building

### CMake

```bash
mkdir build && cd build
cmake ..
make
```

Produces:
- **macOS**: `libcpp-rule-example.dylib`, `libcpp-reporter-example.dylib`
- **Linux**: `libcpp-rule-example.so`, `libcpp-reporter-example.so`
- **Windows**: `cpp-rule-example.dll`, `cpp-reporter-example.dll`

### Makefile

```bash
make
```

### Manual

**macOS:**
```bash
c++ -std=c++17 -Wall -fPIC -dynamiclib -o libcpp-rule-example.dylib src/rule-plugin.cpp
c++ -std=c++17 -Wall -fPIC -dynamiclib -o libcpp-reporter-example.dylib src/reporter-plugin.cpp
```

**Linux:**
```bash
c++ -std=c++17 -Wall -fPIC -shared -o libcpp-rule-example.so src/rule-plugin.cpp
c++ -std=c++17 -Wall -fPIC -shared -o libcpp-reporter-example.so src/reporter-plugin.cpp
```

## Installation

```bash
make install
# or manually:
cp libcpp-rule-example.dylib libcpp-reporter-example.dylib ~/.karandash/plugins/
```

## Configuration

```yaml
rules:
  cpp-title-case: true
  cpp-max-files:
    maxFiles: 30

reporters:
  cpp-reporter-example: true
```

## Project Structure

```
native-plugin-cpp/
├── CMakeLists.txt
├── Makefile
│                         # header: docs/native-sdk/karandash_plugin.h (KARANDASH_SDK)
├── README.md
└── src/
    ├── rule-plugin.cpp     # RULE module: cpp-title-case, cpp-max-files
    └── reporter-plugin.cpp # REPORTER module: markdown format
```
