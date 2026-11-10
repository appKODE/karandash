# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 1.0.x   | Yes       |

## Reporting a Vulnerability

If you discover a security vulnerability in Karandash, please report it responsibly.

### How to Report

1. **Do not** open a public issue for security vulnerabilities
2. Use [GitHub private vulnerability reporting](https://github.com/appKODE/karandash/security/advisories/new) to send the details of the vulnerability to the maintainers
3. Include:
   - Description of the vulnerability
   - Steps to reproduce
   - Potential impact
   - Suggested fix (if any)

### What to Expect

- Acknowledgment within 48 hours
- Status update within 5 business days
- Fix timeline depends on severity:
  - **Critical**: Patch within 48 hours
  - **High**: Patch within 1 week
  - **Medium/Low**: Included in next release

## Security Considerations

### Plugin System

Karandash loads and executes third-party plugins (JAR, JavaScript, WASM, native shared libraries). Users should:

- Only install plugins from trusted sources
- Verify plugin signatures when available (`karandash sign` / `karandash verify`)
- Review plugin source code before installation
- Use the plugin registry (`tools/registry/registry.json`) for vetted plugins

### Configuration

- Environment variables referenced in config files (`${VAR}`) are resolved via an allowlist
- Config inheritance (`extends:`) fetches remote configs over HTTPS; verify the source URL
- KTS config files are parsed via ANTLR (not compiled/executed as Kotlin scripts)

### AI Integration

- The `ai` rule module sends MR metadata to configured AI providers (Anthropic, OpenAI, Ollama)
- Diffs are processed through `SecretRedactor` before being sent to any AI service
- Use `ollama` provider for fully local AI processing with no external data transmission

### Native Plugins

- Native plugins (`.dylib`/`.so`/`.dll`) run with full process privileges
- WASM plugins run in a sandboxed interpreter (Chicory on JVM, Chasm on native) with no filesystem or network access
- JavaScript plugins run in a sandboxed QuickJS runtime with no I/O capabilities
