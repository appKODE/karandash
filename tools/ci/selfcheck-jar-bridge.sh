#!/usr/bin/env bash
# Regression: releases ship the jar-bridge next to the native binary under the name the native
# host looks for (JarBridgeClient.BRIDGE_JAR_NAME): the shadowJar output, the GitHub release asset, the
# Docker build context (.dockerignore) and /app in the native image must all agree.
cd "$(dirname "$0")/../.." || exit 1
JAR=plugins/loader/jar-bridge/build/libs/karandash-jar-bridge.jar
FAIL_PREFIX=jar-bridge
. tools/ci/lib.sh
grep -q 'BRIDGE_JAR_NAME = "karandash-jar-bridge.jar"' plugins/loader/src/nativeMain/kotlin/io/karandash/subprocess/JarBridgeClient.kt ||
    fail "JarBridgeClient.BRIDGE_JAR_NAME is not karandash-jar-bridge.jar"
WF=.github/workflows
grep -rqs ":plugins:loader:jar-bridge:shadowJar" "$WF" || fail "no workflow in $WF builds the jar-bridge"
grep -rqs "karandash-jar-bridge.jar" "$WF" || fail "no workflow in $WF attaches karandash-jar-bridge.jar to the GitHub release"
grep -rqs "BRIDGE_JAR_FILE=$JAR" "$WF" || fail "the native image build in $WF does not pass BRIDGE_JAR_FILE=$JAR"
grep -qx "!$JAR" .dockerignore || fail ".dockerignore drops $JAR from the build context"
grep -q 'COPY ${BRIDGE_JAR_FILE} /app/karandash-jar-bridge.jar' docker/Dockerfile.runtime-native || fail "image lacks /app jar"
grep -q 'COPY ${BINARY_FILE} /app/karandash$' docker/Dockerfile.runtime-native || fail "binary moved off /app/karandash"
# SELFCHECK_STATIC=1 (tools/ci/gate.sh, api-check job): stop before the Gradle build; gate2.sh runs the whole check.
[ "${SELFCHECK_STATIC:-}" = 1 ] && { echo "jar-bridge static OK"; exit 0; }
rm -f "$JAR"
./gradlew :plugins:loader:jar-bridge:shadowJar -q >/dev/null 2>&1 || fail "jar-bridge shadowJar failed"
[ -f "$JAR" ] || fail "shadowJar did not produce $JAR"
unzip -p "$JAR" META-INF/MANIFEST.MF | grep -q "Main-Class: io.karandash.loader.jar.JarBridgeServer" || fail "no Main-Class"
echo "jar-bridge OK"
