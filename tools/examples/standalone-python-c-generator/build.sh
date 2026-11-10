#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "Generating C source from Python..."
python3 "$SCRIPT_DIR/plugin.py"

UNAME_S="$(uname -s)"
if [ "$UNAME_S" = "Darwin" ]; then
    TARGET="$SCRIPT_DIR/libpython-standalone.dylib"
    gcc -std=c11 -Werror=implicit-function-declaration -fPIC -dynamiclib -O2 \
        -o "$TARGET" "$SCRIPT_DIR/generated_plugin.c"
else
    TARGET="$SCRIPT_DIR/libpython-standalone.so"
    gcc -std=c11 -Werror=implicit-function-declaration -fPIC -shared -O2 \
        -o "$TARGET" "$SCRIPT_DIR/generated_plugin.c"
fi

echo "Built: $TARGET"
