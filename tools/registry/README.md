# Karandash Plugin Registry

The official plugin registry for Karandash MR validation tool. This registry contains metadata for all officially supported and community-contributed plugins.

## Registry Format

The registry is a JSON file (`registry.json`) with the following structure:

```json
{
  "$schema": "https://karandash.io/schemas/registry-v1.json",
  "version": "1",
  "lastUpdated": "2026-02-10T00:00:00Z",
  "plugins": [
    {
      "id": "plugin-id",
      "name": "Plugin Name",
      "description": "Brief description of what the plugin does",
      "author": "author-name",
      "version": "0.1.0",
      "type": "rule|reporter",
      "tags": ["tag1", "tag2"],
      "url": "https://download-url/plugin.jar",
      "checksum": "sha256:...",
      "minHostVersion": "0.1.0",
      "repository": "https://github.com/...",
      "documentation": "https://docs-url/"
    }
  ]
}
```

### Field Descriptions

- **id**: Unique identifier for the plugin (lowercase, hyphenated)
- **name**: Human-readable plugin name
- **description**: Brief description (max 200 characters)
- **author**: Plugin author or organization
- **version**: Semantic version (MAJOR.MINOR.PATCH)
- **type**: Plugin type - `rule` (validation rules) or `reporter` (notification/reporting)
- **tags**: Array of searchable tags
- **url**: Direct download URL for the plugin JAR file
- **checksum**: SHA-256 checksum for integrity verification (format: `sha256:<hex>`)
- **minHostVersion**: Minimum Karandash version required
- **repository**: Source code repository URL
- **documentation**: Plugin documentation URL

## How to Submit a Plugin

### Prerequisites

1. Your plugin must implement the Karandash Plugin SDK
2. Plugin must be packaged as a JAR file
3. Plugin must include proper metadata in the JAR manifest
4. Plugin must be hosted at a stable, publicly accessible URL
5. Plugin must have documentation

### Submission Process

1. **Test Your Plugin**
   ```bash
   # Install from local file
   karandash plugin install --plugin-dir /path/to/your-plugin-dir

   # Test with a sample configuration
   karandash validate --config test-config.yml
   ```

2. **Calculate Checksum**
   ```bash
   shasum -a 256 your-plugin.jar
   ```

3. **Prepare Plugin Entry**
   Create a JSON object following the registry format above.

4. **Submit Pull Request**
   - Open a pull request against the [karandash repository](https://github.com/appKODE/karandash)
   - Edit `tools/registry/registry.json` (served raw from `main` as the default registry)
   - Add your plugin entry to the `plugins` array
   - Update the `lastUpdated` timestamp
   - Open the pull request with:
     - Plugin description
     - Link to plugin repository
     - Link to documentation
     - Test results showing the plugin works with current Karandash version

### Review Criteria

Your plugin submission will be reviewed for:

- **Functionality**: Plugin works as described
- **Security**: No malicious code or vulnerabilities
- **Quality**: Code follows best practices
- **Documentation**: Clear usage instructions
- **Testing**: Adequate test coverage
- **Compatibility**: Works with stated minHostVersion
- **Versioning**: Proper semantic versioning

## Plugin Requirements

### Technical Requirements

1. **JAR Packaging**: Plugin must be a valid JAR file
2. **Manifest**: Include proper metadata in `META-INF/MANIFEST.MF`
3. **Dependencies**: All dependencies must be shaded/included
4. **SDK Version**: Use compatible Plugin SDK version
5. **Error Handling**: Proper error handling and logging
6. **Thread Safety**: Plugin must be thread-safe if used concurrently

### Manifest Example

```
Manifest-Version: 1.0
Plugin-Id: my-plugin
Plugin-Name: My Plugin
Plugin-Version: 1.0.0
Plugin-Type: rule
Plugin-Main-Class: com.example.MyPlugin
Plugin-Min-Host-Version: 0.1.0
```

### Security Requirements

1. **No Arbitrary Code Execution**: Plugins must not execute arbitrary shell commands
2. **Credential Handling**: Sensitive data must be handled securely
3. **Network Access**: Document all external network calls
4. **File System Access**: Document all file system operations
5. **Dependency Scanning**: No known vulnerabilities in dependencies

### Versioning Guidelines

Follow [Semantic Versioning 2.0.0](https://semver.org/):

- **MAJOR**: Incompatible API changes
- **MINOR**: Backward-compatible functionality additions
- **PATCH**: Backward-compatible bug fixes

When updating a plugin:
- Increment the version in your plugin
- Update the registry entry with new version, URL, and checksum
- Provide release notes in the PR

## Testing Guidelines

Before submitting, test your plugin with:

### 1. Basic Functionality Test

```bash
# Install plugin
karandash plugin install my-plugin

# Verify installation
karandash plugin list

# Test with minimal config
karandash validate --config minimal-test.yml
```

### 2. Integration Test

Test your plugin with real MR scenarios:

```yaml
# test-config.yml
rules:
  - type: my-plugin
    config:
      # Your plugin configuration
```

### 3. Error Handling Test

Test plugin behavior with:
- Invalid configuration
- Missing required fields
- Network failures (for integrations)
- Malformed input data

### 4. Performance Test

Verify plugin performs acceptably:
- Processing time per MR
- Memory usage
- Resource cleanup

## Built-in Plugins

The following plugins are officially maintained by the Karandash team:

### Rule Plugins

- **jira-plugin**: Validates JIRA ticket references in MRs
- **custom-validator-plugin**: Custom validation rules engine

### Reporter Plugins

- **slack-plugin**: Slack notifications
- **webhook-reporter-plugin**: Generic webhook integration

## Community Resources

- **Documentation**: https://appkode.github.io/karandash/
- **Plugin Development Guide**: https://appkode.github.io/karandash/plugins/creating-plugins/
- **Discord Community**: https://discord.gg/karandash
- **Issue Tracker**: https://github.com/appKODE/karandash/issues

## License

This registry is licensed under the MIT License. Individual plugins may have their own licenses - refer to each plugin's repository for details.
