#!/usr/bin/env bash
# Bakes a Kotlin/Native distribution + its linux_x64 dependencies (LLVM, sysroot, libffi) into $KONAN_DATA_DIR,
# so CI jobs never download the ~220 MB bundle: slow runner links truncate it
# ("Premature end of Content-Length delimited message body"). curl resumes (-C -) across retries.
# Usage: provision-konan.sh <kotlin-version> [maven-mirror]
set -euxo pipefail
ver="$1"
mirror="${2:-https://repo1.maven.org/maven2}"
dist="kotlin-native-prebuilt-linux-x86_64-${ver}"
url="${mirror}/org/jetbrains/kotlin/kotlin-native-prebuilt/${ver}/kotlin-native-prebuilt-${ver}-linux-x86_64.tar.gz"
mkdir -p "$KONAN_DATA_DIR"
for _ in 1 2 3 4 5 6 7 8 9 10; do
    curl -fSL -C - --retry 5 --retry-all-errors -sS -o /tmp/kn.tgz "$url" && break
    sleep 5
done
tar -xzf /tmp/kn.tgz -C "$KONAN_DATA_DIR"
rm /tmp/kn.tgz
touch "$KONAN_DATA_DIR/$dist/provisioned.ok"
# A first compile pulls the target dependencies into $KONAN_DATA_DIR/dependencies.
echo 'fun main() {}' > /tmp/warm.kt
"$KONAN_DATA_DIR/$dist/bin/kotlinc-native" /tmp/warm.kt -o /tmp/warm
rm -rf /tmp/warm*
