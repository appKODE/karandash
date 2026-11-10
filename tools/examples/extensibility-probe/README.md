# Extensibility probe

Answers one question per extensibility mechanism: *can this capability be added as a plugin, compiled only
against the published SDK, without editing core?* Every item is green, and a red one is a regression.

Run everything with:

```bash
tools/ci/verify-extensibility.sh            # BASE=origin/main by default; SKIP_PATH_GUARD=1 to skip (a)
```

Exit codes: `0` all green, `1` at least one item red, `2` setup error (publish failed or `baseline` broken).

## Items

| Item | Where | Mechanism | Green when |
|------|-------|------|-----------|
| baseline | `src/baseline` | - | Always. Plain `RuleModule` on today's SDK; a failure means the probe setup is broken. |
| rule | `src/rule` | run context | `io.karandash.api.run.RunContext` exists and `RuleModule` has `validate(ruleId, mr, configElement, run: RunContext)`. |
| analyzer | `src/analyzer` | typed meta keys | `io.karandash.sdk.finding.MetaKey` + `withMeta`, and `io.karandash.models.finding.PresentationKeys.SECTION` exist. |
| output | `src/output` | output hints | `OutputModule` has `preferredFormat` and `supportsFileOutput`. |
| config | `src/config` | module-owned config | Compiles today (the SDK surface - `configKey`, `TypedModule.configSerializer` - already exists). **Compile-only:** the runtime half of module-owned config (`probe:` claimed into `Config.extensions` and delivered as `pluginConfig`) is not checked here. |
| JS guest | `js/probe-plugin.mjs` | guest declarations | `ExtensibilityProbeGuestContractTest` passes: guest `supportedEnrichment`/`requiredEnrichment`/`needsScopeDetection` reach the host, and an `AI_ANALYZER`-only guest is not a `RuleModule`. |

The import paths for the run-context and meta-key probes follow the planned locations. If a change lands the type in a different
package, update the probe import in the same change - that is the only probe edit it should need.

## How it is built

- `settings.gradle.kts` makes this a **standalone build** (not in the root `settings.gradle.kts`). It resolves
  `ru.kode.karandash:sdk` from `<root>/build/probe-repo` and reuses the root version catalog.
- `tools/ci/extensibility-publish.init.gradle.kts` publishes `:plugins:sdk` and its project-dependency closure
  (`core:api`, `logger`, `models`, `conditions`, `multipattern`, `common`, …) to that repo, signing disabled.
- Each item is its own Gradle source set, so `compileRuleKotlin`, `compileAnalyzerKotlin`, … fail independently.

```bash
./gradlew --no-configuration-cache --init-script tools/ci/extensibility-publish.init.gradle.kts publishSdkToProbeRepo
./gradlew -p tools/examples/extensibility-probe compileBaselineKotlin compileRuleKotlin
./gradlew :plugins:integration-tests:contracts:contractTest --tests '*ExtensibilityProbe*'
```

## Registration

Each source set ships `META-INF/services/io.karandash.api.Module`, which is how `JarPluginLoader` discovers
third-party JVM modules. The probe is **not** listed in `plugins/builtin/builtin-modules.txt`: it is a
third-party stand-in, not a first-party module. A real first-party plugin built this way would add one line
there - that file is on the path guard's allowlist for that reason.

## Not covered

- **WASM guest.** The only reusable prebuilt analyzer (`wasm-plugin-c`'s `ai-analyzer-plugin.wasm`, a build output of its `build.sh`)
  cannot declare enrichment or scope detection without rebuilding it from source, so it could only probe the
  phantom-`RuleModule` half of the JS guest check, which the JS guest already covers.
