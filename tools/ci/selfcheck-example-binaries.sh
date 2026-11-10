#!/usr/bin/env bash
# Regression: example binaries are build outputs, not committed files; .dockerignore keeps competitors.
set -euo pipefail
cd "$(dirname "$0")/../.."

fail=0

tracked=$(git ls-files 'tools/examples/**/*.wasm' 'tools/examples/*.wasm')
if [ -n "$tracked" ]; then
    echo "example-binaries: committed example binaries (build them instead):"
    echo "$tracked"
    fail=1
fi

if ! git check-ignore -q tools/examples/wasm-plugin-c/plugin.wasm; then
    echo "example-binaries: .gitignore does not cover tools/examples/**/*.wasm"
    fail=1
fi

if grep -qx 'competitors' .dockerignore; then
    echo "example-binaries: .dockerignore excludes competitors"
    fail=1
fi

for old in native-plugin-python standalone-python-plugin; do
    if [ -d "tools/examples/$old" ]; then
        echo "example-binaries: tools/examples/$old is a C generator and must carry the c-generator name"
        fail=1
    fi
done

if grep -q 'sh.rustup.rs' docker/Dockerfile.base || ! grep -q 'sha256sum -c' docker/Dockerfile.base; then
    echo "example-binaries: docker/Dockerfile.base must install rustup-init pinned by version and sha256, not curl | sh"
    fail=1
fi

# CI runs gate.sh (.github/workflows/ci.yml, gate job)
grep -q ':plugins:loader:wasmExamplesTest' tools/ci/gate.sh || { echo "example-binaries: tools/ci/gate.sh does not run :plugins:loader:wasmExamplesTest"; fail=1; }
grep -q 'bash tools/ci/gate.sh' .github/workflows/ci.yml || { echo "example-binaries: .github/workflows/ci.yml does not run tools/ci/gate.sh"; fail=1; }

exit "$fail"
