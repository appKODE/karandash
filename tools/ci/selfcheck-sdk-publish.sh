#!/usr/bin/env bash
# Regression: tag releases publish the plugin SDK to Maven Central (ru.kode.karandash:sdk), where the
# `plugin create` / `generate` scaffold resolves it from mavenCentral(). Checks that a release workflow runs
# publishToMavenCentral, that karandash.publishing is the vanniktech Central convention, that the scaffold asks for
# ru.kode.karandash:sdk from mavenCentral(), and runs publishSdkToProbeRepo to prove the SDK and its project
# dependencies publish under ru.kode.karandash at the project version.
cd "$(dirname "$0")/../.." || exit 1
FAIL_PREFIX=sdk-publish
. tools/ci/lib.sh
grep -rqsE "publish(AndRelease)?ToMavenCentral" .github/workflows || fail "no workflow in .github/workflows runs publishToMavenCentral"
CONV=build-logic/src/main/kotlin/karandash.publishing.gradle.kts
grep -q 'id("com.vanniktech.maven.publish")' "$CONV" || fail "karandash.publishing does not apply com.vanniktech.maven.publish"
grep -q 'group = "ru.kode.karandash"' "$CONV" || fail "karandash.publishing does not set group ru.kode.karandash"
grep -q "publishToMavenCentral()" "$CONV" || fail "karandash.publishing does not target Maven Central"
TEMPLATES=plugins/registry/src/commonMain/kotlin/io/karandash/registry/PluginTemplates.kt
grep -q 'ru.kode.karandash:sdk:{{SDK_VERSION}}' "$TEMPLATES" || fail "scaffold does not depend on ru.kode.karandash:sdk"
grep -q 'mavenCentral()' "$TEMPLATES" || fail "scaffold does not resolve from mavenCentral()"
version=$(grep -o '^    version = "[^"]*"' build.gradle.kts | sed 's/.*"\(.*\)"/\1/')
[ -n "$version" ] || fail "no project version in build.gradle.kts"
# SELFCHECK_STATIC=1: stop before the Gradle build.
[ "${SELFCHECK_STATIC:-}" = 1 ] && { echo "sdk-publish static OK"; exit 0; }
repo=build/probe-repo/ru/kode/karandash
rm -rf build/probe-repo
log=$(mktemp)
trap 'rm -f "$log"' EXIT
./gradlew --init-script tools/ci/extensibility-publish.init.gradle.kts publishSdkToProbeRepo -q >"$log" 2>&1 ||
    { tail -20 "$log"; fail "publishSdkToProbeRepo failed"; }
for f in "sdk/$version/sdk-$version.module" "sdk/$version/sdk-$version.pom"; do
    [ -f "$repo/$f" ] || fail "missing ru/kode/karandash/$f"
done
grep -q '<url>https://github.com/appKODE/karandash</url>' "$repo/sdk/$version/sdk-$version.pom" || fail "SDK POM url is not the GitHub repo"
[ -f "$repo/api-jvm/$version/api-jvm-$version.jar" ] || fail "SDK dependency core:api not published under ru.kode.karandash"
echo "sdk-publish OK"
