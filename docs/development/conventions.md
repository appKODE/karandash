# Contributor conventions

Technical rules for contributing to Karandash: build commands, module layout, architecture constraints, lint
policy and testing requirements. Lint rules are enforced by CI (`ktlint`, `detekt`, and the project's own
`karandash` detekt rule set); the rest is enforced by code review.

## Commands

Always run from the repo root (this directory).

```bash
./gradlew build                          # full build: JVM + native target(s) for host OS
./gradlew jvmTest                        # JVM tests only (fast, ~10-30s)
./gradlew macosArm64Test                 # native tests (macOS ARM64); linuxX64Test on Linux
./gradlew ktlintCheck detekt             # lint + static analysis (CI gate, zero issues)

bash tools/ci/gate.sh                   # pre-merge gate part 1: format, JVM tests, koverVerify, apiCheck, lint, native link, benchmark check, integration scripts
KARANDASH_PREV_JAR=prev.jar bash tools/ci/gate2.sh  # gate part 2: isolation, extensibility, dokka, mkdocs, compat-diff vs a previous release jar
./gradlew ktlintFormat                   # auto-fix formatting - run before committing
./gradlew :plugins:sdk:apiCheck :core:api:apiCheck   # public API binary-compatibility check
./gradlew koverXmlReport koverHtmlReport koverVerify # coverage; per-module floors in gradle/kover-floors.properties
./gradlew :cli:shadowJar                 # standalone fat JAR
```

Single test class/method (Gradle test filtering):
```bash
./gradlew :core:api:jvmTest --tests "io.karandash.SomeClassTest"
./gradlew :core:api:jvmTest --tests "io.karandash.SomeClassTest.someMethod"
```

Module paths mirror the directory structure (see [Module Structure](#module-structure)) - replace `:core:api` with the target module's Gradle path.

## Module Structure

`core/` holds fine-grained sub-modules under a parent (`ls core/` for the current set). Gradle paths follow
the directory: `:core:api`, `:core:models`, `:core:cache`, etc.

Plugins are organized into three tiers - the tier decides whether a module is always shipped, opt-in, or
part of the AI subsystem, which the directory names alone don't tell you:

```
plugins/
  plugins-core/       - Tier 1: always required (GitLab/GitHub/Bitbucket platform services, console, 9 core rules, parsers, minimal profile)
  plugins-extended/   - Tier 2: opt-in (niche platform services: Azure DevOps, Gerrit, Gitea, AWS, Jenkins; enrichment providers, extra reporters/rules/profiles, scripts)
  plugins-ai/         - Tier 3: AI subsystem (ai-core, ~15 analyzers, LLM providers, reports, metrics)
  integration-tests/  - Cross-tier integration tests (rules/, platforms/)
  sdk/                - Public plugin API
  builtin/            - factory registrations for all first-party modules (manifest: `builtin-modules.txt`)
  bootstrap/          - `PluginBootstrap`: runs built-in + JAR/native/JS/WASM loaders into `ModuleRegistry` at CLI startup
  inspector/          - reads/verifies plugin files without loading them (`plugin list`, install verify)
  registry/           - public plugin registry client (`plugin install/update/list`, `generate`)
  helpers/            - Shared HTTP helpers
  loader/             - Plugin loading (JAR, JS, WASM, Native) + jar-bridge/
```

Additional modules:
- `cli/` - command implementations; `cli-ai/` - AI-tier commands (`stats`, `calibrate`, `test-rule`, `verify-policy`, `mutate`, `explain`) plus `SuspendCliktCommand`/`ExitCodes`, registered by `cli` (`cli` → `cli-ai`, never the reverse); `cli-app/` - JVM `main()` entry point (`karandash.jvm-application`)
- `projects/` - dogfooding config trees (`base` → `base-kmp` → `wallet`, etc.) exercising `extends:` inheritance; some are Gradle modules with tests
- `tools/` - `examples/` (canonical example plugins), `ci/verify-plugin-isolation.sh`, `registry/`, `benchmarks/`, `templates/`
- `build-logic/` - convention plugins, `GenerateBuiltInRegistryTask`, dependency-guard

## Plugin System Terminology

| Code Interface | Config Block | Purpose |
|----------------|--------------|---------|
| `PipelineExtension` | (auto-discovered) | Pre/post validation hooks |

Full hierarchy: `Plugin > Module > {RuleModule, ReporterModule, PlatformServiceModule, EnrichmentProviderModule, PipelineExtension}`

## Architecture

### Kotlin Multiplatform

Convention plugins in `build-logic/` are the source of truth for target set, `expect/actual` wiring, and Gradle DSL rules - read the relevant `karandash.*` plugin there before touching module build files.

**New modules must use convention plugins.** Every new KMP module's `build.gradle.kts` must apply `karandash.kmp-library` (or `karandash.kmp-platform-service-module` for `PlatformServiceModule` implementations, or `karandash.jvm-application` for JVM-only entry points). This automatically configures:
- All 4 native targets (macOS ARM64/x64, Linux x64, Windows MinGW) + JVM
- ktlint (via `karandash.lint`) and detekt - lint is active from the first build
- Source set completeness validation - all 4 target-specific source sets must be declared

Never apply raw `kotlin("multiplatform")` or configure ktlint/detekt manually in module `build.gradle.kts` files - the convention plugin handles it.

**Target gating:** On macOS, `karandash.kmp-library` only declares macosArm64 + macosX64 targets by default. Modules that are dependencies of `plugins/sdk` (or any module with `karandash.ungatedTargets=true`) must also set `karandash.ungatedTargets=true` in their own `gradle.properties` - otherwise Gradle cannot resolve linuxX64/mingwX64 variants and IDE sync fails.

**`commonMain` is mandatory for all new code.** Platform-specific source sets must contain only `actual` declarations and their direct platform helpers.

### Dependency Direction

Dependencies form a strict DAG:

```
cli → core/* → core/api → stdlib
core/* → plugins/sdk                (allowed; never the reverse)
cli → plugins/sdk → core/api + SDK-public core leaves
plugins/{plugins-core,plugins-extended,plugins-ai}/* → plugins/sdk
dsl/* → core/api
dsl/lsp-server → plugins/sdk, plugins/bootstrap, plugins/loader   (registry access for hover and completion)
```

**Hard rules:**
- `core/*` must NOT depend on `cli`, `dsl`, or plugin tier modules - enforced at configuration time by the
  guard in `build-logic/.../karandash.kmp-library.gradle.kts` (test configurations exempt; any known edge awaiting
  removal goes in the `DependencyDirection` allowlist with its audit ID - currently empty)
- `core/*` may depend on `plugins/sdk` (`core/config`, `core/state`, `core/validation`, `core/ai-pipeline` do);
  `plugins/sdk` must never depend back on them
- `plugins/sdk` must NOT depend on `core/*` other than `core/api` and the SDK-public core leaves it re-exports
  via `api()` (`core/logger`, `core/models`, `core/conditions`, `core/multipattern`, `core/common`) - those are
  public ABI and are covered by `apiCheck`; the one internal exception is `core/regex-safety` (pulls in
  `core/cache`), an `implementation()` dependency backing `SafeRegex` and kept out of the SDK ABI on purpose;
  never on `cli` or plugin tier modules
- Plugin tier modules must NOT depend on `cli`
- `core/*` and `dsl/*` must NOT import concrete SDK module types (use `fun interface` injection for cross-layer calls)
- Exception types must be defined in `core/api` or `plugins/sdk` only - never in leaf modules

```kotlin
// BAD - core importing a concrete plugin module
import io.karandash.plugins.modules.rules.ai.AiRuleModule

// GOOD - core depends on an interface; concrete type injected at CLI layer
fun interface RuleEvaluator {
    suspend fun evaluate(mr: MergeRequest, config: JsonElement): List<Finding>
}
```

### Validation Flow

```
CLI command → Config pipeline (JsonElement throughout)
           → PipelineExtension.fetchOptions / needsBlame / needsHeadContent (what the fetch must include)
           → PlatformServiceModule.fetchMergeRequest(url) → MergeRequest
           → PipelineExtension.onConfigured(env)        (env.platformId = the provider that fetched)
           → PipelineExtension.onBaseFiles(files)       (only for extensions whose baseFiles() is non-empty)
           → PipelineExtension.onPostFetch(mr, config)
           → ValidationEngine.validate(mr)
               → for each RuleModule: validate(ruleId, mr, configElement: JsonElement) → List<Finding>
           → PipelineExtension.afterValidation(findings, mr, config) → List<Finding>
           → ValidationResult
           → PipelineExtension.beforeReporting(findings, mr) → List<Finding>
           → for each ReporterModule: report(findings, config: JsonElement)
           → PipelineExtension.onPipelineComplete()
```

### Object Lifecycle

Two lifecycle categories - mixing them causes subtle bugs.

**Per-run (`@lifecycle per-run`):** `ValidationEngine`, `ValidationPipeline`, `ValidateCommandScope`. New instance per CLI invocation; mutable fields permitted. Mark with KDoc:
```kotlin
/** @lifecycle per-run -- do not reuse across invocations */
class ValidationPipeline(...)
```

**Application-scoped:** `ModuleRegistryImpl`, `CacheManager`, `GlobMatcher`. Must be thread-safe and stateless between calls. Mutable fields require concurrency protection.

Per-run lifecycle is enforced by code review only.

### Plugin System

**All new functionality must be implemented as a plugin** - including built-in features. Design new capability as a `plugins/sdk` module.

`plugins/sdk` is a **public API** - only add fields, never remove or rename. All plugins receive `PluginContext` (config as `JsonElement`, logging, env vars filtered by `KarandashEnvSecurity.DENYLIST`).

#### SDK API Evolution Rules

- New interface methods **must** have a default implementation so existing plugins continue to compile
- Signature changes: add new method with default → deprecate old → remove in next major version
- `api/*.api` files are checked into git and reviewed on each PR
- `binary-compatibility-validator` is applied to `plugins/sdk` and `core/api` - run `apiDump` after an intentional API change

#### Module Lifecycle & Statelessness

**Lifecycle:** `initialize(ctx): Boolean` (once; return `false` on bad config - never throw) → primary method (0+ times) → `shutdown()` (at most once, idempotent, must not throw, releases all resources). `types()` must return ALL types for multi-type modules.

**Allowed fields:** `val` config set in `initialize()`, `HttpClient` from `initialize()`, logger via sanctioned pattern:
```kotlin
private var logger: Logger = NoOpLogger
override suspend fun initialize(ctx: PluginContext): Boolean { logger = ctx.logger; return true }
```

`lateinit var` is permitted for resources created in `initialize()` that require construction arguments unavailable at class instantiation time (e.g. `HttpClient` needing a logger from `PluginContext`). These must be cleaned up in `shutdown()`:
```kotlin
private lateinit var httpClient: HttpClient
override suspend fun initialize(ctx: PluginContext): Boolean {
    httpClient = ProviderHttpClientFactory.create(logger = ctx.logger); return true
}
override suspend fun shutdown() { if (::httpClient.isInitialized) httpClient.close() }
```

**Forbidden fields:** `lateinit var` for storing `PluginContext` itself, `var` accumulating cross-call state, unprotected mutable collections.

**Known violators:** grep for `lateinit var context: PluginContext` and `private var` fields other than the sanctioned `logger` in module classes. Fix when touching those files.

#### Plugin Languages & Loading

- JVM: JAR via `ServiceLoader`, native via JNA, JS via QuickJS, WASM via Chicory
- Native: JAR via subprocess (`JarBridgeServer`), native via `dlopen`, JS via QuickJS, WASM via Chasm (pure Kotlin, compiled in; 2.2.0, except the macOS x64 binary on 1.7.0, whose store cannot be interrupted)
- Loader: `plugins/loader` module (`JarPluginLoader`, `NativePluginLoader`, `JsPluginLoader`)

Bridge completeness is checked by `BridgeAbiParityTest` (`plugins/loader/src/jvmTest/`). Module contract tests live in `plugins/integration-tests/{rules,providers}/` (`*ContractTest`).

#### Sanctioned `runBlocking` Sites

`runBlocking` is forbidden in production code except at tracked bridge boundaries (a non-suspend caller that must call a suspend method). Every usage requires a `// bridge-boundary: <reason>` comment within the 3 lines above it or as the first line of its block:
- CLI entry: `KarandashCli.kt` (`runCli`), `SuspendCliktCommand.run()`, the `ValidateCommand` watch callback
- JS/QuickJS: `JsTransport`, `JsContextInjector`
- `CAbiBridge` (C ABI exports), `JarBridgeServer` (blocking stdin/stdout subprocess)
- `BridgeTransport.callSync` default (non-suspend SDK members such as `tokensConsumed`, `costSummary` answered by a guest call)
- Host-service channel: `HostServiceDispatcher.call` (a guest's `karandash_host_call` on its own thread, never main; `Dispatchers.Default`)
- LSP: `dsl/lsp-server` `Main.kt`, `CachedRegistryMetadata`, `ConfigPipelineDiagnostics`
- On native targets: must use a dedicated coroutine context to avoid deadlocking the main dispatcher

detekt `karandash:UnsanctionedRunBlocking` (`sanctionedFiles` in `detekt.yml`) lists exactly these files and checks the comment; adding a site means adding it here and there.

The AI bridge methods `AiRedactorModule.redact` and `AiTokenAllocatorModule.allocate` are `suspend`, so the AI bridges need no `runBlocking`.

#### Example Plugin Canonicality

`tools/examples/` must follow every rule in this file. Rule changes affecting module patterns must update example projects in the same commit. CI runs detekt on example projects.

### Config Loading Pipeline

Formats (discovery order, first match wins):
1. `.karandash/config.kts` → Kotlin DSL (ANTLR4 via `dsl/kts-parser`)
2. `.karandash/config.yaml` / `.karandash/config.yml` → YAML
3. `.karandash/config.json` → JSON

Pipeline steps:
1. Detect via `ConfigDiscovery`; route via `ConfigFormatRegistry` (sorted by `priority`)
2. Parse to `JsonElement`; process as `JsonObject` throughout `ConfigMapParser`
3. Resolve `{ file: "..." }` references via `ExternalValueResolver` in `ConfigChainParser` (paths relative to config file's directory; auth blocks excluded via security denylist)
4. Apply `extends:` inheritance via `ConfigChainResolver.buildChain` + `ConfigChainParser.parseAndMerge` (`validate extends` only prints the chain)
5. Interpolate `${ENV_VAR}` via `PlatformEnvironment` (`KarandashEnvSecurity.DENYLIST` enforced)
6. Normalize keys once via `deepNormalizeKeys()` (kebab-case, snake_case → camelCase)
7. Validate schema → `Config` delivered to `ValidationEngine`
8. Zero-config fallback via `ZeroConfigDefaults` (queries registry for `isDefaultZeroConfigProfile`)

Note: `ExternalValueResolver` is called by `ConfigChainParser`, not `ConfigMapParser` - do not add a second resolution pass.

### Config Schema Evolution Policy

- New optional fields with defaults: permitted
- New required fields: forbidden without a zero-config default
- Renamed fields: keep old name as alias with deprecation warning at `warn` level
- Removed fields: forbidden without a two-release deprecation window
- Any change to a golden under `cli/src/jvmTest/resources/compat/` (effective config, output snapshots) is a behavior change: regenerate with `-Dkarandash.updateGolden=true` and record it under CHANGELOG `## [Unreleased]`

### HTTP

Ktor with platform-specific engines: CIO (JVM), curl (macOS/Linux native), WinHTTP (Windows). Always use `ProviderHttpClientFactory` - never create Ktor clients directly. No custom retry logic - use Ktor `HttpRequestRetry` plugin. Do not set per-request timeouts in module code.

## Code Quality (Lint)

ktlint v1.5.0 + detekt 2.0.0-alpha.6 (`dev.detekt`). Zero issues: every rule is severity error and fails the task. Run `./gradlew ktlintFormat` before committing.

Project conventions that need no type resolution are detekt rules too: the `karandash` rule set in
`tools/detekt-rules` (`GenericCatchWithoutBoundary`, `SuspendCatchWithoutCancellation`, `ForbiddenRunCatching`,
`ForbiddenWithTimeout`, `UnsanctionedRunBlocking`, `UnownedCoroutineScope`, `RawConsoleOutput`, `InlineJsonBuilder`,
`DirectHttpClient`, `DirectGetenv`, `UntypedConfigMap`, `SuppressOutsidePolicy`, `DynamicRegex`), applied to every module and
`tools/examples`; sanctioned files are rule config in `detekt.yml`.

**Zero-tolerance for red builds.** After any change, run lint and tests until all checks are green. Fix every failure - including pre-existing issues in files you touched. Do not leave known-broken tests or lint violations for later.

### Write compliant code from the start

Do not write code that violates lint rules and fix it later - read the configs and write within the thresholds from the beginning. Before writing any new class or function, consult:

- **detekt:** `detekt.yml` (repo root) - complexity thresholds (class size, method length, cyclomatic complexity, parameter counts, nesting depth, etc.) and active rule sets
- **ktlint:** `.editorconfig` (repo root) - code style, line length, import rules, indentation

Design classes and methods to fit within these limits from the start. If a class approaches the size threshold, decompose it immediately rather than writing a large class and refactoring later. Writing within these constraints from the start avoids fix-up cycles and keeps detekt at zero issues throughout development.

### @Suppress Policy - Fix, Don't Suppress

**`@Suppress` is forbidden by default.** Never add class-level or file-level suppression. detekt `karandash:SuppressOutsidePolicy` rejects any id outside its `allowedIds` in `detekt.yml` (the categories below).

**Permitted exceptions:**

| Category | Example | Required condition |
|----------|---------|-------------------|
| Kotlin compiler opt-in | `@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")`, `@Suppress("OPT_IN_USAGE")` | No fix path until language feature stabilises |
| C-interop / native ABI stubs | `@Suppress("EmptyFunctionBlock")` on native plugin stub `shutdown()`, `@Suppress("UnusedParameter")` on a C callback | Body structurally cannot be non-empty / the C signature fixes the parameters; add a `// native-abi: <reason>` comment |
| `expect`/`actual` symmetry | `@Suppress("UnusedParameter")` on `actual` constructors | `actual` must mirror `expect` signature |
| Boundary `UNCHECKED_CAST` | YAML/JSON/KTS parser boundaries, untyped cache payloads | Parser library returns untyped map; no type-safe alternative; add a `// parser-boundary:` or `// boundary:` comment |
| Public API with no current callers | SDK extension functions, API overloads | Must add KDoc explaining retention |
| `TooGenericExceptionCaught` at module boundaries | Top-level of `validate()`, `report()`, `fetchMergeRequest()`, bridge, CLI entry | Must add `// boundary: <reason>` comment |

**Fix, don't suppress:**

| Violation | Correct fix |
|-----------|------------|
| `LargeClass` (>500 lines) | Extract responsibilities (Strategy, Stage, Adapter, Facade) |
| `LongMethod` | Extract private methods or a dedicated class |
| `CyclomaticComplexMethod` | Replace `when` chains with polymorphism or sealed class dispatch |
| `LongParameterList` | Group params into a dedicated options/config `data class` |
| `ThrowsCount` | Consolidate into sealed result type |
| `UnusedParameter` (non-interface/non-actual) | Remove; deprecation cycle for public API |
| `UseCheckOrError` | `error(msg)` |
| `UseRequire` | `require(condition) { msg }` |
| `CollapsibleIfStatements`, `ProtectedMemberInFinalClass`, `ReturnCount`, `SwallowedException` | Fix the code structure |

**`LargeClass` threshold: 500 lines** (detekt 2 counts KDoc lines too) (200 lines for LSP server - see [LSP Server Rules](#lsp-server-rules)).

## Code Patterns & Style

### Null Safety

**No `!!` operator.** Use `checkNotNull(value) { "message" }` or `requireNotNull(value) { "message" }`. Enforced by detekt `UnsafeCallOnNullableType`.

### Error Handling

| Situation | Approach |
|-----------|---------|
| Config parse failure | Throw `ConfigParseException` - caught and shown to user |
| Validator cannot evaluate | Return empty `List<Finding>` - log via `context.warn()`, never throw |
| Platform API failure | `Result<T>` and propagate |
| Domain errors with multiple cases | Sealed class hierarchy |
| Unexpected/programming error | `error(msg)` / `require(condition) { msg }` |

### Exception Handling - Catch Specificity

**Never catch generic `Exception` or `Throwable`** - catch specific types:

| Context | Catch type |
|---------|-----------|
| HTTP calls | `IOException`, `HttpRequestTimeoutException` |
| JSON parsing | `SerializationException`, `IllegalArgumentException` |
| Config loading | `ConfigParseException`, `IOException` |
| Plugin `initialize()` | `ConfigParseException` |
| Module boundary (validate/report/fetch) | `Exception` with `// boundary: <reason>` comment |

**Never swallow exceptions silently** - every catch must log at `warn` or rethrow as `KarandashException` subtype.

**`init {}` blocks must not discard return values.** If initialization can fail, use a companion factory returning `Result<T>`.

### Immutability & Data Classes

**`var` is forbidden** except inside `@lifecycle per-run` classes. Every field/property/local must be `val`. Enforced by detekt `VarCouldBeVal`.

**All `data class` declarations must be `@Serializable`.** Only exception: runtime context holders (see [@Serializable Contract](#serializable-contract)).

- Use `copy()` for all transformations - never mutate a model instance
- Collections returned from functions: `List<T>` (read-only), never `MutableList<T>`
- `MutableList`/`MutableMap`/`MutableSet`: permitted only as local variables inside function bodies, never as class fields

### Coroutines & Coroutine Safety

- Use `suspend fun` - `runBlocking` only at `main()` in `cli-app/` and in tests via `runTest {}`
- Use structured concurrency (`coroutineScope`, `supervisorScope`) - never `GlobalScope`
- All plugin module interfaces use `suspend fun`; blocking I/O uses `withContext(Dispatchers.IO)`

**`Dispatchers.IO` KMP import** - requires both imports or it resolves to JVM-only:
```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO  // required - common declaration
withContext(Dispatchers.IO) { readFile() }
```

**`CancellationException` must always be rethrown.** In every `catch` in a `suspend fun`, call `e.rethrowCancellation()` (defined in `plugins/sdk`) before handling. Enforced by the `karandash` detekt rule set (`tools/detekt-rules`): `SuspendCatchWithoutCancellation` flags an `IllegalStateException`/`RuntimeException` catch in a `suspend fun` that neither rethrows cancellation nor follows a `CancellationException` catch, and `GenericCatchWithoutBoundary` flags an `Exception`/`Throwable` catch without a `// boundary: <reason>` comment inside it.

**`safeCall {}` wrapper must be `suspend` and rethrow `CancellationException`.**

**No `PlatformLock` / `platformSynchronized`.** Use KMP primitives:

| Need | Solution |
|------|----------|
| Mutual exclusion | `Mutex` from `kotlinx.coroutines.sync` |
| Observable state | `MutableStateFlow` |
| Hot-path primitives | `AtomicInt`, `AtomicLong`, `AtomicBoolean`, `AtomicRef` from `atomicfu` |

**atomicfu** already configured in build-logic. Never use `java.util.concurrent.atomic.*`.

**No mutable shared collections as class fields.** Use `AtomicRef.update {}` or `Mutex.withLock {}` + copy-on-write. Exception: read-only maps built once at `initialize()` as `val`.

### Module ID & Cross-Module Constants

**A module ID is defined exactly once** - in the module's `companion object`. No other file may hardcode the same string.

**If module B needs module A's ID but cannot depend on A**, in priority order:
1. Decouple via SDK flags (`ReporterModule.isDefaultInteractive`, `ProfileModule.isDefaultZeroConfigProfile`) or `fun interface`
2. Define in `core/config/src/commonMain/kotlin/io/karandash/config/keys/` (e.g., `ModuleIds.GITLAB`); both modules import from there
3. Never add a dependency just to read an ID

**Core key objects:** `ModuleIds`, `PolicyKeys` in `core/config/src/commonMain/kotlin/io/karandash/config/keys/`; `EnrichmentKeys` lives in `plugins/sdk/…/sdk/enrichment/` and the `EnrichmentGlobMatcher` helper in `core/glob`. New shared string constants belong here.

## Config & Data Layer - Mandatory Rules

**All config data flows as `JsonElement`. `Map<String, Any?>` is forbidden in production code.** The only exception is a
parser boundary (YAML/KTS parser output, the `mapToJsonElement` bridge), marked with a `// parser-boundary: <reason>`
comment on the line, within the two lines above it or on the line below; detekt `karandash:UntypedConfigMap` fails anything else in `commonMain`.

### JsonElement-first

| Rule | Details |
|------|---------|
| **SDK interfaces use `JsonElement` only** | `PluginContext.pluginConfig: JsonElement`, `Module.defaultConfig(): JsonElement`, `ReporterModule.report(config: JsonElement)`, `RuleModule.validate(configElement: JsonElement)`. Never add `Map<String, Any?>` to SDK interfaces. |
| **No `toAnyMap()` in production code** | Exists only for test code and parser boundaries. Never call in `cli/src/commonMain/` or `plugins/*/src/commonMain/`. |
| **No `ConfigSpec.fromMap()`** | Use `configSpec.fromJson(element)`. |
| **No `MapBackedTypedConfig` / `asTypedConfig()`** | Removed. Use `TypedKey`, `TypedRuleModule`, `AbstractTypedSingleRuleModule` (JsonElement-backed, in `plugins/sdk`). |
| **No `flexField()` / `flexGet()`** | Keys normalized at parse time via `deepNormalizeKeys()`. Use `JsonObject["camelCaseKey"]`. |
| **No `mapToJsonElement()` in hot paths** | Bridge for parser boundaries only; never for data that could be `JsonElement` from the start. |
| **Config data classes must be `@Serializable`** | Use `@SerialName` for kebab/snake-case keys. Deserialize via `Json.decodeFromJsonElement()`. |
| **No inline `Json {}` blocks** | Use `KarandashJson.Default` or `KarandashJson.Strict` from `core-models`. |

### @Serializable Contract

**Every `data class` must be `@Serializable`** - domain models, config types, SDK types, internal DTOs, test fixtures.

**Documented exception - runtime context holders.** A `data class` that carries lambdas, `Throwable`, `Regex`,
`Path`, untyped `Any` or live services cannot be serialized and stays unannotated, with a KDoc line
`Not @Serializable: <why>.` directly above it (e.g. `ModuleResult.Failure`, `ConditionContext`,
`PluginCommandSpec`, `AiAnalyzerContext`, `ReportContext`, the `dsl/kts-parser` AST). A data class without either
the annotation or that line is a violation.

Checklist when adding any `data class`:
1. `@Serializable` annotation
2. `@SerialName` for fields whose JSON key differs from Kotlin name
3. Use `KarandashJson.Default` - no inline `Json {}` blocks
4. Verify with `serializer<T>()` call (SDK types checked by audit test in `plugins/sdk/src/commonTest/`)

### Pipeline Decoupling

| Rule | Details |
|------|---------|
| **`ValidationPipeline` must not import AI classes** | AI integration goes through `PipelineExtension` via `ModuleRegistry`. Zero `import io.karandash.plugins.ai.*` in `ValidationPipeline.kt`. |
| **`AutoFixer` must not parse finding messages** | Use `finding.fixSuggestions` (structured `FixSuggestion` sealed class), never `finding.message.startsWith(...)`. |
| **No hardcoded module IDs in generic pipeline code** | Use SDK flags: `ReporterModule.isDefaultInteractive`, `ProfileModule.isDefaultZeroConfigProfile`, `ConfigFormatModule.priority`. |
| **New built-in modules go in `plugins/builtin/builtin-modules.txt`** | `BuiltInPluginLoader` factory list is generated from this manifest at build time. |
| **No double-parse config paths** | Config parsed once via `ConfigChainParser`. Do not re-parse `JsonElement` in module code. |

## Key Conventions

- **No magic strings for module IDs**: use `companion object` constants (e.g., `GitLabPlatformService.ID`, `ConsoleReporterModule.FORMAT_ID`)
- **No magic strings for defaults**: constants in companion objects or `core/config/src/commonMain/kotlin/io/karandash/config/keys/` objects
- **`MergeRequestState.fromString()`**: normalize platform state strings to enum
- **`ConfigParseException`**: throw for user-facing config parse errors in `dsl/kts-parser` and `cli`
- **`GlobMatcher`**: use `GlobMatcher.Default` (sized per run by `GlobMatcher.useManaged(cacheManager)`); reuse instances, don't create per-call
- **`ProviderResponseCache`**: built once per run by `PipelineInfrastructure` and passed through `PipelineRunResources`
- **`SecretRedactor`**: always run diffs through this before sending to LLM in `plugins/plugins-ai/rules/ai`
- Rule modules return empty list on failure; log via `context.warn()`
- `Finding.location` carries `FileRef` or `CommitRef` for inline annotation support
- `Finding.fixSuggestions` carries structured `FixSuggestion` (AddLabel, RemoveLabel, SetDraft, SetTitle, SetDescription)
- **No `println()` / `print()`** - use `Logger` from `core-logger` or Mordant. Enforced by detekt `karandash:RawConsoleOutput` (the stdout/stderr sinks are its `excludes` in `detekt.yml`).
- Test fixtures: use factory functions, not builder classes

## Testing

Default to `commonTest` (shared across all targets); `jvmTest` for JVM-only cases (mokkery). Assertions via `kotlin.test`, coroutines via `runTest {}`. No live API calls - use Ktor `MockEngine` for HTTP stubs.

Shared common test support lives in `test-fixtures`: `GoldenFiles` (golden assertions; `-Dkarandash.updateGolden=true` reaches every JVM and native test process as `KARANDASH_UPDATE_GOLDEN`, `-Dkarandash.updateBaseline` as `KARANDASH_UPDATE_BASELINE` - wired in `karandash.kmp-library` via `build-logic/.../TestEnvironment.kt`) and `TestFiles` (temp dirs, text I/O, `findRepoRoot()`). Native test processes get `KARANDASH_EVAL_LIVE` and provider tokens blanked, so they can never go live.

**Backtick test names: letters, digits, spaces, `-` and `_` only.** No `,` `.` `;` `:` `/` `\` `<` `>` `[` `]` quotes - Kotlin/Native fails with `Name contains illegal characters`, while JVM accepts them, so only the native gate step catches it.

Additional tests required for this repo:
- **Module contract tests**: lifecycle invariants per module type (`plugins/integration-tests/`, `*ContractTest`)
- **Bridge completeness test**: `BridgeAbiParityTest` in `plugins/loader/src/jvmTest/` - every guest-implementable SDK member needs a `BridgeAbi` op, a descriptor key or a `NOT_BRIDGED` reason
- **SDK serialization audit test**: `plugins/sdk/src/commonTest/` - calls `serializer<T>()` for every public data class in `io.karandash.models` and `io.karandash.sdk`

## KMP API Rules

All `commonMain` code must use KMP-compatible APIs. Most of the table below is enforced by code review only: `detekt.yml` `ForbiddenImport` bans just `java.time.*` and Ktor `encodeBase64`/`decodeBase64` outside `jvmMain`, and the `karandash` detekt rules catch `getenv(` (`DirectGetenv`), `HttpClient(` (`DirectHttpClient`), inline `Json {}` (`InlineJsonBuilder`) and `Map<String, Any?>` in `commonMain` (`UntypedConfigMap`).

| Category | Must Use | Banned |
|----------|----------|--------|
| Timestamps / Duration | `kotlin.time.Clock`, `kotlin.time.Instant`, `kotlin.time.Duration` | `java.time.*`, `java.util.Date`, `System.currentTimeMillis()`, `System.nanoTime()` |
| Local dates / TZ | `kotlinx-datetime` (`LocalDate`, `LocalDateTime`, `TimeZone`) | `java.time.*` |
| File I/O | `kotlinx-io` (`Path`, `Source`, `Sink`) | `java.io.File`, `java.io.*Stream`, `java.nio.*` |
| Coroutines | `kotlinx-coroutines-core` | `java.lang.Thread`, `ExecutorService`, `Future` |
| IO dispatcher | `Dispatchers.IO` + `import kotlinx.coroutines.IO` | `Dispatchers.IO` without KMP import |
| Atomics | `kotlinx-atomicfu` | `java.util.concurrent.atomic.*` |
| Concurrency | `Mutex`, `MutableStateFlow`, `CompletableDeferred`, `kotlinx.coroutines.sync.Semaphore` | `ConcurrentHashMap`, `CopyOnWriteArrayList`, `java.util.concurrent.locks.*`, `CountDownLatch`, `Semaphore` (java) |
| Collections | `mutableListOf()`, `mutableMapOf()`, `mutableSetOf()`, `ArrayDeque` | `java.util.ArrayList`, `java.util.HashMap`, `java.util.Stack`, `java.util.Properties` |
| UUID | `com.benasher44:uuid` | `java.util.UUID` |
| Random | `kotlin.random.Random` | `java.util.Random` |
| Base64 | `kotlin.io.encoding.Base64` | `java.util.Base64`, `io.ktor.util.encodeBase64` |
| Math | `kotlin.math.*` | `java.lang.Math.*` |
| Logging | `Logger` from `core-logger` | `java.util.logging.*` |
| Env vars | `PlatformEnvironment.getEnv()` | `System.getenv()`, `platform.posix.getenv()`, `System.getProperty()` |
| HTTP client | Ktor via `ProviderHttpClientFactory` | Direct Ktor client creation |

Do not introduce JVM-only libraries (Apache Commons, Guava, etc.) in `commonMain`.

## Security Conventions

Detekt enforces some rules at lint time (`ForbiddenImport`, `karandash:DynamicRegex`); the rest are convention-based.

- **Regex:** Never call `Regex()` on user-supplied patterns. Use `RegexHelper.tryCompile()` (returns `Regex?`, appends `Finding`) or `RegexHelper.compileOrNull()` (for `init` blocks). Direct `Regex()` / `toRegex()` only on a pattern written in the source (a literal, optionally with `${Regex.escape(...)}` pieces), enforced per call by detekt `karandash:DynamicRegex`; its `excludes` are the guarded compile points only.
- **URL validation:** All user-configured outbound URLs must pass through `SsrfUrlValidator.validate()` before HTTP requests.
- **Env vars:** Never read directly. Use `PlatformEnvironment.getEnv()`. It does not filter anything itself: `KarandashEnvSecurity.DENYLIST` is applied by config interpolation (`ConfigMapParser.interpolate`) and by the plugin context (`BootstrapPluginContext`), so code reading env for config or plugins must go through those paths.
- **Cache sizing:** All in-memory caches must be bounded with LRU eviction. Follow `GlobMatcher` pattern (fixed-size `LinkedHashMap` with `removeEldestEntry`).
- **Path normalization:** Always normalize paths/URLs before equality checks. Follow `ConfigChainResolver.normalizeUrl()` to prevent path traversal bypasses.

## LSP Server Rules

`dsl/lsp-server/` has stricter requirements than the rest of the repository (JVM-only, LSP4J).

- **Class size:** keep classes under 200 lines (stricter than the repo's 499-line detekt `LargeClass` limit, which is the
  only automatic check). Split by responsibility, as `CompletionSupport`, `CompletionTables` and `DiagnosticSupport` do.
- **Registry access:** read module metadata (rule ids, providers, reporters, ...) through `CachedRegistryMetadata`, which
  queries the `ModuleRegistry` once at construction. Do not query the registry per request. The one other registry
  consumer is `ConfigPipelineDiagnostics`, which runs the CLI's `ConfigCheck` on the loaded `PluginBootstrap`.
- **Thread safety:** LSP4J calls handlers from its thread pool. Providers hold only immutable state built at
  construction; no shared mutable collections or `var` fields.
- **`runBlocking`:** LSP4J handlers are synchronous while registry and parser APIs are `suspend`. It is allowed only in
  `Main.kt`, `CachedRegistryMetadata.kt` and `ConfigPipelineDiagnostics.kt` (the detekt `UnsanctionedRunBlocking`
  list), each with a `// bridge-boundary: <reason>` comment.
- **Diagnostics = `config validate`:** `ConfigPipelineDiagnostics` runs `ConfigCheck` (`:cli`) with the editor buffer
  as the document's content, so `extends:` parents and `{ file: }` references resolve as on the command line. Positions
  come from each message's key path (first matching line per segment); do not add a second config check here.
