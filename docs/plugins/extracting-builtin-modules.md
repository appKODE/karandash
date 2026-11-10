# Extracting Built-in Modules

This guide covers how to extract a built-in Karandash module into a standalone plugin JAR, and documents which sealed types in the SDK are open for plugin-defined subtypes.

## What Extraction Means

A built-in module ships inside the Karandash binary and is registered in `plugins/builtin/builtin-modules.txt`. Extracting it means:

- Moving the module's source into a standalone Gradle project
- Compiling it against `plugins/sdk` only (no `core` dependency)
- Distributing it as a JAR loaded via `ServiceLoader` or as a native shared library

The result is a module that any user can override, fork, or disable - and that the Karandash team can release on an independent versioning cadence.

## Requirements for a Standalone Module

1. **Depend on `plugins/sdk` only.** No imports from `io.karandash.core.*`, `io.karandash.cli.*`, or any other internal package.
2. **Use only public SDK types.** No `internal` classes, no reflection tricks against internal APIs.
3. **Register via `ServiceLoader`.** Add `META-INF/services/io.karandash.sdk.Module` listing the module's fully-qualified class name.
4. **Be `@Serializable`-clean.** Config data classes must be `@Serializable`; never rely on `Map<String, Any?>` or `toAnyMap()`.
5. **Target all platforms.** The module must compile for JVM, macOS ARM64/x64, Linux x64, and Windows MinGW if distributed as a KMP artifact, or ship as a JVM JAR loaded via the subprocess bridge on native.

## Priority Candidates

| Module | Location | Primary coupling to resolve |
|--------|----------|-----------------------------|
| **AI rules** | `plugins/plugins-ai/rules/ai/` | `AiService` is already in SDK; check for `core` imports |
| **JIRA integration** | `plugins/plugins-extended/rules/jira-ticket/` | HTTP client via `ProviderHttpClientFactory` (in `plugins/helpers`) - move helpers dep to SDK or replicate |
| **Email output** | `plugins/plugins-extended/outputs/email-output/` | JVM `JavaMail` vs native SMTP split - verify `expect/actual` boundary is clean |

## Common Coupling Issues

- **Direct `core` imports** - e.g., `import io.karandash.core.config.Config`. Replace with `JsonElement`-based config access.
- **`internal` API usage** - any `internal` symbol is not part of the public SDK. Identify via `lsp_find_references` and replace with a public equivalent or request promotion.
- **Shared utilities not in SDK** - `RegexHelper`, `GlobMatcher`, `SsrfUrlValidator` live in `core`. Candidates for promotion to `plugins/sdk` or `plugins/helpers` before extraction.
- **`plugins/builtin/builtin-modules.txt` registration** - remove the entry after extraction; add the external JAR to the user's `plugins:` block in config.

## Extraction Checklist

1. **Audit imports** - run `grep -r "io.karandash.core" <module>/src/` and list every hit.
2. **Resolve each `core` import** - either the type needs promotion to SDK, or the module must be refactored to use `JsonElement` accessors directly.
3. **Create standalone Gradle project** - depend only on `plugins/sdk` (and `plugins/helpers` if HTTP is needed).
4. **Add `ServiceLoader` registration** - `META-INF/services/io.karandash.sdk.Module`.
5. **Port tests to `commonTest`** - remove any test helpers that import `core`.
6. **Remove from `plugins/builtin/builtin-modules.txt`** - verify the binary builds and the module loads from the external JAR.
7. **Publish and document** - update `tools/registry/registry.json` and add a note in the relevant `docs/rules/` or `docs/reporters/` page.

---

## Sealed Class Extensibility Audit

The SDK uses sealed classes and enums to model core domain types. This section documents which are intentionally closed and which support plugin-defined subtypes.

| Type | Kind | Plugin subtypes? | Rationale |
|------|------|-----------------|-----------|
| `FixSuggestion` | Sealed class | Yes - `FixSuggestion.Custom` added | Plugins need to express domain-specific auto-fixes beyond the built-in set (AddLabel, RemoveLabel, SetDraft, SetTitle, SetDescription). |
| `Location` (`FileRef`, `CommitRef`) | Sealed class | No | Used in exhaustive `when` expressions throughout reporters and comment posters. Adding subtypes would silently break all existing `when` branches. |
| `Severity` | Enum | No | Fixed domain: `ERROR`, `WARNING`, `INFO`. Extending it would break severity-ordering logic and reporter thresholds. |
| `MergeRequestState` | Enum | No | Fixed domain tied to platform API mappings via `fromString()`. New states require platform provider changes, not plugin subtypes. |
| `ModuleType` | Enum | No - use `fromString()` | The `fromString()` method accepts arbitrary strings for runtime module type discovery without requiring enum extension. |
| `Finding` | Data class | N/A - not sealed | Already open. Plugins return `Finding` instances directly; no subtyping needed. |

### Decision rule for future types

Add a `Custom` subtype (or equivalent open extension point) when:
- External plugins need to return instances of the type from their own logic (as with `FixSuggestion`)
- The set of valid values is genuinely open-ended

Keep sealed / enum when:
- The type is consumed in exhaustive `when` expressions where an unknown subtype would silently fall through
- The domain is closed and controlled by platform integrations (states, severities)
- Extension is possible via a string-keyed registry pattern (as with `ModuleType.fromString()`)
