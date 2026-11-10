#!/usr/bin/env bash
# Downloads karandash.jar from the GitHub release of appKODE/karandash and checks it against the release SHA256SUMS.
# Env: INPUT_VERSION ("latest" or a tag like v1.46.0), GITHUB_TOKEN (optional, raises the API rate limit when resolving
#      "latest"), KARANDASH_RELEASE_BASE (override of the release download base, used by the smoke test),
#      KARANDASH_RELEASE_API (override of the repository API base), GITHUB_OUTPUT.
set -euo pipefail
API="${KARANDASH_RELEASE_API:-https://api.github.com/repos/appKODE/karandash}"
BASE="${KARANDASH_RELEASE_BASE:-https://github.com/appKODE/karandash/releases/download}"
VERSION="${INPUT_VERSION:-latest}"

if [ "$VERSION" = "latest" ]; then
  body=$(curl --fail --silent --show-error -L ${GITHUB_TOKEN:+-H "Authorization: Bearer $GITHUB_TOKEN"} \
    "$API/releases/latest")
  VERSION=$(grep -m1 '"tag_name"' <<<"$body" | sed -E 's/.*"tag_name"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/' || true)
  [ -n "$VERSION" ] || { echo "Error: could not resolve the latest release" >&2; exit 1; }
fi
echo "Using Karandash $VERSION"

for f in karandash.jar SHA256SUMS; do
  curl --fail --silent --show-error -L -o "$f" "$BASE/$VERSION/$f" ||
    { echo "Error: failed to download $BASE/$VERSION/$f" >&2; exit 1; }
done

expected=$(grep -E '[[:space:]]\*?karandash\.jar$' SHA256SUMS | cut -d' ' -f1)
if command -v sha256sum > /dev/null; then
  actual=$(sha256sum karandash.jar | cut -d' ' -f1)
else
  actual=$(shasum -a 256 karandash.jar | cut -d' ' -f1)
fi
rm -f SHA256SUMS
if [ -z "$expected" ] || [ "$expected" != "$actual" ]; then
  rm -f karandash.jar
  echo "Error: karandash.jar checksum mismatch (expected '${expected}', got '${actual}')" >&2
  exit 1
fi
echo "version=$VERSION" >> "${GITHUB_OUTPUT:-/dev/null}"
