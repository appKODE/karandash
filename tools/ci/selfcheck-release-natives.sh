#!/usr/bin/env bash
# Regression: a release tag ships native binaries for every target the CLI builds: linux-x64, macOS arm64,
# macOS x64 and Windows x64. Checks that the release-build workflow links each binary and keeps it as an artifact,
# that the assets job renames it to its release asset name (with SHA256SUMS), and that the release uploads the assets.
cd "$(dirname "$0")/../.." || exit 1
FAIL_PREFIX=release-natives
. tools/ci/lib.sh
CI=.github/workflows/release-build.yml
grep -q "uses: ./$CI" .github/workflows/release.yml || fail "release.yml does not call $CI"
built=$(ci_job build)
assets=$(ci_job assets)
release=$(ci_job release)
[ -n "$built" ] && [ -n "$assets" ] && [ -n "$release" ] || fail "$CI lacks the build, assets or release job"
check() { # task, binary path, asset name
    printf '%s\n' "$built" | grep -q ":cli:$1" || fail "the build job does not run :cli:$1"
    printf '%s\n' "$built" | grep -qF -- "$2" || fail "the build job does not keep $2 as an artifact"
    # artifact paths drop their common prefix: the copy names the binary's target dir, or just the file for a one-file artifact
    line=$(printf '%s\n' "$assets" | grep -F " dist/$3" | grep "^ *cp ")
    target=${2#cli/build/bin/}
    case "$line" in
        *"$target dist/$3" | *"/build-mingw/${2##*/} dist/$3") ;;
        *) fail "the assets job does not ship $2 as $3" ;;
    esac
}
check linkReleaseExecutableLinuxX64 cli/build/bin/linuxX64/releaseExecutable/cli.kexe karandash-linux-x64
check linkReleaseExecutableMacosArm64 cli/build/bin/macosArm64/releaseExecutable/cli.kexe karandash-macos-arm64
check linkReleaseExecutableMacosX64 cli/build/bin/macosX64/releaseExecutable/cli.kexe karandash-macos-x64
check linkReleaseExecutableMingwX64 cli/build/bin/mingwX64/releaseExecutable/cli.exe karandash-windows-x64.exe
printf '%s\n' "$built" | grep -q 'os: macos-' || fail "the macOS binaries are not built on a macOS runner"
printf '%s\n' "$assets" | grep -qF 'sha256sum -- * > SHA256SUMS' || fail "the assets job writes no SHA256SUMS"
printf '%s\n' "$release" | grep -qF 'name: release-assets' || fail "the release job does not download the assets"
printf '%s\n' "$release" | grep -qF 'gh release create "$TAG" dist/*' || fail "the release job does not upload the assets"
# the SDK publish is not required: without Maven Central the binaries, jar and images still release
printf '%s\n' "$release" | grep 'needs:' | grep -q maven && fail "the release needs the maven job"
echo "release-natives OK"
