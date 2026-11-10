#!/bin/bash
# Karandash MR Validation Tool
# Simple wrapper script for the CLI

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_PATH="$SCRIPT_DIR/cli/build/libs/karandash.jar"

if [ ! -f "$JAR_PATH" ]; then
    echo "Error: JAR file not found. Please build first with: ./gradlew :cli:shadowJar"
    exit 1
fi

exec java -jar "$JAR_PATH" "$@"
