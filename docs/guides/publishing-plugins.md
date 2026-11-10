# Publishing Plugins

Publish your Karandash plugins for use by the community or internally within your organization.

## Introduction

### Why Publish Plugins?

Publishing plugins makes them:

- **Discoverable**: Listed in the official registry for easy discovery
- **Reusable**: Available for other projects and teams
- **Maintainable**: Centralized versioning and update management
- **Professional**: Establishes your organization as a contributor

### Publishing Options

You can publish your plugin in several ways:

1. **Official Registry**: Submit to the Karandash registry (requires approval)
2. **Maven Central**: Publish JVM plugins to Maven Central for dependency management
3. **Custom Registry**: Host your own registry for private or experimental plugins
4. **Direct Distribution**: Share JAR files directly with colleagues or teams

## Preparing Your Plugin

Before publishing, make sure your plugin meets publication standards.

### 1. Semantic Versioning

Use [Semantic Versioning 2.0.0](https://semver.org/) for version numbers:

```
MAJOR.MINOR.PATCH

- MAJOR: Incompatible API changes
- MINOR: Backward-compatible feature additions
- PATCH: Backward-compatible bug fixes
```

Examples:
- `1.0.0` - First release
- `1.1.0` - New feature, backward compatible
- `1.0.1` - Bug fix
- `2.0.0` - Breaking changes

### 2. Write Tests

Test your plugin thoroughly:

```bash
# Test with various configurations
karandash validate --config test-configs/valid.yml
karandash validate --config test-configs/invalid.yml
karandash validate --config test-configs/edge-cases.yml

# Test error handling
karandash validate --config test-configs/missing-fields.yml
```

Example test structure:

```
tests/
  ├── valid/
  │   ├── simple.yml
  │   └── complex.yml
  ├── invalid/
  │   ├── malformed.yml
  │   └── missing-config.yml
  └── edge-cases/
      ├── empty-mr.yml
      └── large-mr.yml
```

### 3. Document Your Plugin

Create clear documentation:

```markdown
# My Custom Plugin

## Overview
Brief description of what the plugin does.

## Installation
```bash
karandash plugin install my-plugin
```

## Configuration
```yaml
rules:
  - type: my-plugin
    config:
      option1: value1
      option2: value2
```

## Usage Examples
Practical examples showing the plugin in action.

## Troubleshooting
Common issues and solutions.
```

### 4. Choose a Unique Plugin ID

Plugin IDs must be:

- Lowercase alphanumeric with hyphens
- Descriptive of the plugin's purpose
- Unique across the registry
- Between 3-50 characters

Examples:
- `jira-validator`
- `slack-reporter`
- `custom-lint-rules`
- `security-scanner`

Check for existing IDs in the [registry.json](https://github.com/appKODE/karandash/blob/main/tools/registry/registry.json).

### 5. Create a Manifest (JVM Plugins)

Add manifest metadata to your JAR:

```gradle
// build.gradle.kts
jar {
    manifest {
        attributes(
            "Plugin-Id" to "my-plugin",
            "Plugin-Name" to "My Custom Plugin",
            "Plugin-Version" to "1.0.0",
            "Plugin-Type" to "rule",  // or "reporter"
            "Plugin-Main-Class" to "com.example.MyPlugin",
            "Plugin-Min-Host-Version" to "0.1.0"
        )
    }
}
```

## Publishing to Maven Central (JVM Plugins)

Make your plugin available as a regular Maven dependency. The Karandash SDK (`ru.kode.karandash:sdk`) is
published the same way, with the [vanniktech maven-publish plugin](https://vanniktech.github.io/gradle-maven-publish-plugin/)
and the [Central Portal](https://central.sonatype.com).

### Prerequisites

1. **Central Portal account and namespace**: sign in at [central.sonatype.com](https://central.sonatype.com) and verify
   the namespace you publish under (e.g. `com.example`).
2. **User token**: generate one in the portal (Account -> Generate User Token).
3. **GPG key**: `gpg --full-generate-key`, then publish the public key with
   `gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>`.

### Configure build.gradle.kts

```kotlin
plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish") version "0.37.0"
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates("com.example", "my-custom-plugin", "1.0.0")
    pom {
        name.set("My Custom Plugin")
        description.set("Karandash rule plugin")
        url.set("https://github.com/yourorg/my-custom-plugin")
        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }
        developers {
            developer {
                id.set("yourid")
                name.set("Your Name")
            }
        }
        scm {
            url.set("https://github.com/yourorg/my-custom-plugin")
        }
    }
}
```

### Configure Credentials

In `~/.gradle/gradle.properties`, or as `ORG_GRADLE_PROJECT_<name>` environment variables in CI:

```properties
mavenCentralUsername=<token username>
mavenCentralPassword=<token password>
signingInMemoryKey=<ASCII-armored private key>
signingInMemoryKeyPassword=<key passphrase>
```

### Publish

```bash
./gradlew publishAndReleaseToMavenCentral --no-configuration-cache
```

Once the deployment is validated, users add the plugin as a dependency:

```kotlin
dependencies {
    implementation("com.example:my-custom-plugin:1.0.0")
}
```

## Submitting to the Official Registry

Make your plugin officially discoverable.

### Prerequisites

1. **Working plugin**: Tested and documented
2. **Public repository**: GitHub or similar
3. **GitHub account**: For pull requests
4. **Checksum**: SHA-256 hash of your JAR

### Step 1: Prepare Plugin Entry

Calculate the SHA-256 checksum:

```bash
# macOS/Linux
shasum -a 256 my-plugin.jar

# Output example:
# a1b2c3d4e5f6... my-plugin.jar
```

### Step 2: Create Pull Request

1. Fork [appKODE/karandash](https://github.com/appKODE/karandash)
2. Edit `tools/registry/registry.json`
3. Add your plugin entry to the `plugins` array

Example entry:

```json
{
  "id": "my-custom-plugin",
  "name": "My Custom Plugin",
  "description": "Validates MR compliance against custom rules",
  "author": "Your Organization",
  "version": "1.0.0",
  "type": "rule",
  "tags": ["validation", "custom", "compliance"],
  "url": "https://github.com/yourorg/my-custom-plugin/releases/download/v1.0.0/my-custom-plugin.jar",
  "checksum": "sha256:a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0u1v2w3x4y5z6a7b8c9d0e1f2",
  "minHostVersion": "0.1.0",
  "repository": "https://github.com/yourorg/my-custom-plugin",
  "documentation": "https://github.com/yourorg/my-custom-plugin/blob/main/README.md"
}
```

4. Update `lastUpdated` timestamp:

```json
{
  "$schema": "https://karandash.io/schemas/registry-v1.json",
  "version": "1",
  "lastUpdated": "2026-02-10T15:30:00Z",
  "plugins": [...]
}
```

5. Submit pull request with:
   - Plugin description and purpose
   - Link to documentation
   - Link to source repository
   - Test results showing compatibility
   - Release notes

### Step 3: Review Process

Your plugin will be reviewed for:

| Criterion | Details |
|-----------|---------|
| **Functionality** | Plugin works as described, no breaking bugs |
| **Security** | No malicious code, safe dependencies, documented network access |
| **Quality** | Code follows best practices, proper error handling |
| **Documentation** | Clear usage, configuration options, examples |
| **Testing** | Adequate test coverage, verified with current Karandash |
| **Compatibility** | Works with stated `minHostVersion` |
| **Versioning** | Proper semantic versioning, changelog included |

### Step 4: Maintenance

Once approved:

- **Updates**: Submit new PRs when releasing updated versions
- **Bug fixes**: Update with new version number
- **Security fixes**: Use patch version, prompt notification recommended

## Hosting Your Own Registry

Create a custom registry for private or experimental plugins.

### Step 1: Create plugins.json

Create a file following the registry schema:

```json
{
  "$schema": "https://karandash.io/schemas/registry-v1.json",
  "version": "1",
  "lastUpdated": "2026-02-10T10:00:00Z",
  "plugins": [
    {
      "id": "internal-validator",
      "name": "Internal Validator",
      "description": "Company-specific validation rules",
      "author": "Your Company",
      "version": "1.0.0",
      "type": "rule",
      "tags": ["internal", "validation"],
      "url": "https://artifacts.company.com/plugins/internal-validator-1.0.0.jar",
      "checksum": "sha256:abc123...",
      "minHostVersion": "0.1.0",
      "repository": "https://git.company.com/validation/internal-validator",
      "documentation": "https://wiki.company.com/karandash/internal-validator"
    }
  ]
}
```

### Step 2: Hosting Options

Choose where to host your registry:

#### GitHub Pages

```bash
# Create gh-pages branch
git checkout --orphan gh-pages
git rm -rf .

# Add plugins.json
echo '{"version":"1","plugins":[]}' > plugins.json
git add plugins.json
git commit -m "Initial registry"
git push origin gh-pages
```

Access at: `https://yourorg.github.io/karandash-registry/plugins.json`

#### GitHub Releases

```bash
# Upload plugins.json to releases
gh release create v1.0.0 plugins.json
```

Access at: `https://github.com/yourorg/karandash-registry/releases/download/v1.0.0/plugins.json`

#### AWS S3

```bash
# Upload to S3
aws s3 cp plugins.json s3://my-bucket/karandash/plugins.json --acl public-read
```

Access at: `https://s3.amazonaws.com/my-bucket/karandash/plugins.json`

#### Self-Hosted Server

```bash
# Copy to web server
scp plugins.json user@server:/var/www/karandash/
```

Access at: `https://your-domain.com/karandash/plugins.json`

### Step 3: Tell Users About Your Registry

Configure users to use your custom registry:

```bash
# One-time setup: writes plugins.registry-url to .karandash/manifest.yaml
karandash plugin registry set https://your-domain.com/karandash/plugins.json

# Check which registry is in use
karandash plugin registry show

# Install from it by plugin ID
karandash plugin install --registry my-plugin
```

## Best Practices

### Semantic Versioning

Follow versioning strictly:

- **1.0.0** → **1.0.1**: Bug fix (patch)
- **1.0.0** → **1.1.0**: New feature (minor)
- **1.0.0** → **2.0.0**: Breaking change (major)

Never reuse version numbers.

### Maintain a Changelog

Document changes for each release:

```markdown
# Changelog

## [1.1.0] - 2026-02-10
### Added
- Support for custom regex patterns
- Configuration validation on startup

### Fixed
- Crash when MR description is empty
- Memory leak in rule caching

## [1.0.0] - 2026-01-15
### Added
- Initial release
- Basic validation functionality
```

### Backward Compatibility

Maintain compatibility within major versions:

```kotlin
// GOOD: Backward compatible (minor version bump)
// Old code still works, new parameter optional
fun validate(rule: Rule, mr: MergeRequest, config: Config = defaultConfig)

// BAD: Breaking change (major version bump)
// Old code breaks
fun validate(rule: Rule, mr: MergeRequest) // removed config parameter
```

### Security Considerations

1. **Dependencies**: Keep dependencies up-to-date
   ```bash
   # Check for vulnerabilities
   ./gradlew dependencyCheckAnalyze
   ```

2. **Code Review**: Have others review before release
3. **No Hard-Coded Secrets**: Use configuration instead
4. **Validate Input**: Don't trust MR data, validate strictly
5. **Document External Calls**: Clearly document any network/file access

### Plugin Size

Keep JAR files reasonable:

- Aim for < 10MB
- Shade/include dependencies rather than depending on them
- Use `shadowJar` for Gradle:

```gradle
plugins {
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

shadowJar {
    archiveClassifier.set("")
    minimize()
}
```

## Troubleshooting

### Plugin fails to load from registry

**Problem**: `karandash plugin install my-plugin` fails

**Solutions**:
- Verify plugin ID exists in registry
- Check network connectivity to registry URL
- Verify JAR file is still at download URL
- Check Karandash version matches `minHostVersion`

```bash
# Verify registry is accessible
curl https://karandash.io/registry/plugins.json

# Check if JAR is accessible
curl -I https://github.com/.../releases/download/.../my-plugin.jar
```

### Checksum mismatch

**Problem**: "Checksum verification failed"

**Solutions**:
- Recalculate checksum:
  ```bash
  shasum -a 256 my-plugin.jar
  ```
- Update registry with correct checksum
- Make sure correct JAR file is at download URL
- Verify file wasn't corrupted during upload

### Registry submission rejected

**Problem**: "Your plugin doesn't meet our criteria"

**Common reasons**:
- Missing or incomplete documentation
- Insufficient test coverage
- Security concerns (check security review comments)
- Incompatible with stated `minHostVersion`
- Missing changelog or release notes

**Solution**: Address feedback, update plugin, resubmit PR

### Version conflicts

**Problem**: "Multiple versions installed, conflicts detected"

**Solutions**:
- Use explicit version in configuration:
  ```toml
  [[plugins]]
  id = "my-plugin"
  version = "1.0.0"  # Specify exact version
  ```
- Uninstall conflicting versions:
  ```bash
  karandash plugin uninstall my-plugin
  ```

### JAR not found at download URL

**Problem**: "HTTP 404 when downloading plugin"

**Solutions**:
- Verify URL in registry is correct
- Check file permissions on hosting server
- Make sure CDN/hosting service hasn't deleted old releases
- Update registry with new URL if hosting changed

## Summary

Publishing your Karandash plugin makes it available to the community and simplifies dependency management:

1. **Prepare**: Version, test, document, choose unique ID
2. **Publish to Maven Central** (JVM): For wide distribution
3. **Submit to Official Registry**: For community discovery
4. **Host Custom Registry**: For private/internal plugins
5. **Maintain**: Update versions, fix bugs, follow semantic versioning

See the [Creating Plugins](../plugins/creating-plugins.md) guide for plugin development details.
