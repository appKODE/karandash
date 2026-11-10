# Karandash Documentation

The fastest, most extensible merge request validation tool.

## Features

- **Fast**: Native binary with ~50ms startup
- **Many rules**: 21 built-in validation rules
- **Extensible**: Plugin system for custom logic (Kotlin/JS)
- **Simple**: Kotlin-like DSL or YAML configuration
- **Multi-platform**: GitLab, GitHub, Bitbucket, Azure DevOps, Gerrit, Gitea, AWS CodeCommit

## Quick Start

```bash
# Create config
karandash init

# Validate MR
karandash validate
```

## Documentation Structure

### Getting Started
- [Installation](getting-started/installation.md) - Install Karandash on your platform
- [Quick Start](getting-started/quick-start.md) - Get up and running in 5 minutes

### Configuration
- [YAML Configuration](configuration/YAML_CONFIG.md) - YAML-based configuration guide
- [Zero Config Mode](configuration/ZERO_CONFIG.md) - Run with sensible defaults

### Rules
- [Rules Overview](rules/index.md) - All available validation rules
- Individual rule documentation for approvals, branch naming, CI/CD status, and more

### Plugins
- [Plugin Overview](plugins/overview.md) - Plugin system introduction
- [Plugin Tutorial](plugins/tutorial.md) - Step-by-step plugin development
- [Native Plugin Guide](plugins/native-plugin-guide.md) - Kotlin Multiplatform plugins
- [JS Plugin Guide](plugins/js-plugin-guide.md) - JavaScript plugins

### Platform Guides
- [Platform Support](platforms/index.md) - All supported platforms
- [GitLab](platforms/gitlab.md), [GitHub](platforms/github.md), [Bitbucket](platforms/bitbucket.md), [Azure DevOps](platforms/azure-devops.md), [Gerrit](platforms/gerrit.md), [Gitea](platforms/gitea.md), [AWS CodeCommit](platforms/aws-codecommit.md)

### Integrations
- [CI/CD Integration](integrations/ci-cd-integration.md) - GitHub Actions, GitLab CI, Jenkins, etc.

### Guides
- [Publishing Plugins](guides/publishing-plugins.md) - Share your plugins
- [Troubleshooting](guides/troubleshooting.md) - Common issues and solutions

### Features
- [Auto-Fix](features/auto-fix.md) - Automatic issue resolution
- [Watch Mode](features/watch-mode.md) - Real-time validation
- [Config Inheritance](features/config-inheritance.md) - Shared configurations

### Advanced
- [Build from Source](advanced/build-from-source.md) - Build Karandash yourself
- [Architecture](advanced/ARCHITECTURE_MULTIPLATFORM.md) - Multiplatform architecture
- [Plugin System](advanced/MULTIPLATFORM_PLUGIN_SYSTEM.md) - Deep dive into plugin architecture

### Reference
- [CLI Commands](reference/cli-reference.md) - Complete CLI reference

## Support

- [GitHub Issues](https://github.com/appKODE/karandash/issues)
- [Discussions](https://github.com/appKODE/karandash/discussions)
