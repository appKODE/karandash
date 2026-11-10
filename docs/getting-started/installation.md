# Installation

Learn how to install Karandash on your system.

## Direct Download (GitHub Releases)

Every release tag publishes its files to [GitHub Releases](https://github.com/appKODE/karandash/releases),
together with a `SHA256SUMS` file:

```bash
BASE=https://github.com/appKODE/karandash/releases/download/v1.0.0
curl -fsSLO "$BASE/karandash.jar" -fsSLO "$BASE/SHA256SUMS"
grep ' karandash.jar$' SHA256SUMS | sha256sum -c -   # macOS: shasum -a 256 -c -
java -jar karandash.jar --version
```

Release files: `karandash.jar` (Java 17+), `karandash-linux-x64`, `karandash-macos-arm64`,
`karandash-macos-x64`, `karandash-windows-x64.exe` (native, no JRE) and `karandash-jar-bridge.jar`
(lets the native binary load JAR plugins; put it next to the binary or in `~/.karandash/lib/`).

## Docker

Images are on Docker Hub as `rinekri/karandash`:

| Tag | Contents |
|-----|----------|
| `rinekri/karandash:v1.0.0` | native binary, `linux/amd64` |
| `rinekri/karandash:v1.0.0-jvm` | JVM, `linux/amd64` and `linux/arm64` |
| `latest`, `latest-jvm` | the newest release |

```bash
docker run --rm -v "$PWD:/work" -w /work rinekri/karandash:v1.0.0 validate --help
```

The binary is on `PATH` as `karandash`, so the image works directly as a CI job image.

## GitHub Actions

```yaml
- uses: appKODE/karandash@v1.0.0
  with:
    version: v1.0.0
```

See [GitHub](../platforms/github.md) for the inputs and the runner requirements.

## GitLab CI

```yaml
karandash-validate:
  image: rinekri/karandash:v1.0.0
  script:
    - karandash validate --config .karandash/config.yaml
  rules:
    - if: '$CI_PIPELINE_SOURCE == "merge_request_event"'
```

Or include the reusable template straight from GitHub:

```yaml
include:
  - remote: 'https://raw.githubusercontent.com/appKODE/karandash/v1.0.0/.gitlab/templates/validate.yml'
```

## From Source

Build the standalone fat JAR:

```bash
git clone https://github.com/appKODE/karandash.git
cd karandash
./gradlew :cli:shadowJar
# produces cli/build/libs/karandash.jar

java -jar cli/build/libs/karandash.jar --version
```

To get a `karandash` command on your `PATH`, add a small wrapper:

```bash
mkdir -p ~/.local/bin
cat > ~/.local/bin/karandash <<EOF
#!/bin/sh
exec java -jar "$PWD/cli/build/libs/karandash.jar" "\$@"
EOF
chmod +x ~/.local/bin/karandash
```

`./gradlew installDist` also works, but the `application` plugin is not
configured with a custom `applicationName`, so the launcher it produces is named
after the module - `cli-app/build/install/cli-app/bin/cli-app`, not `karandash`.

A GraalVM native binary is available via `./gradlew :cli-app:nativeImage`
(requires GraalVM with `native-image`), which writes
`cli-app/build/native/karandash`.

## Verify Installation

```bash
karandash --version
```

## Next Steps

- Continue to [Quick Start](quick-start.md)
- Learn about [Rules](../rules/index.md)
- Explore [Plugins](../plugins/overview.md)
