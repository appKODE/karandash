# Module Removal Protocol

How to extract a built-in module from the karandash monorepo and publish it as a standalone plugin.

## Prerequisites

Before removing a module, verify it's standalone-ready:

```bash
./tools/scripts/verify-plugin-standalone.sh :plugins:plugins-core:rules:title-format
```

## Step 1: Build and publish the native binary

```bash
./tools/scripts/publish-plugin.sh :plugins:plugins-core:rules:title-format
```

This produces platform-specific binaries (.dylib/.so/.dll) and a registry entry JSON snippet.

## Step 2: Add to the plugin registry

Add the JSON snippet from Step 1 to `tools/registry/registry.json`.

## Step 3: Remove from the monorepo

Four files need editing:

1. **Delete the module directory**
   ```bash
   rm -rf plugins/plugins-core/rules/title-format
   ```

2. **Remove from `settings.gradle.kts`**
   Delete the line: `include(":plugins:plugins-core:rules:title-format")`

3. **Remove from `plugins/builtin/builtin-modules.txt`**
   Delete the line containing the module's factory class (e.g., `io.karandash.validators.TitleFormatRuleModule`)

4. **Remove from `plugins/builtin/build.gradle.kts`**
   Delete: `implementation(project(":plugins:plugins-core:rules:title-format"))`

## Step 4: Verify the build

```bash
./gradlew jvmTest
./tools/ci/verify-plugin-isolation.sh
```

## Step 5: Users install the extracted plugin

Users add it to their manifest file (`.karandash/manifest.yaml`):

```yaml
pluginDeclarations:
  - id: "title-format"
    version: "1.0.0"
```

Or place the native binary directly in `~/.karandash/plugins/`.

## Module Isolation Rules

All plugin modules must follow these isolation rules:

- **Tier 1 (plugins-core)**: May depend on `plugins:sdk`, `plugins:helpers`, `core:*`
- **Tier 2 (plugins-extended)**: May depend on `plugins:sdk`, `plugins:helpers`, `core:*`
- **Tier 3 (plugins-ai)**: May depend on `plugins:sdk`, `plugins:helpers`, `core:*`, and within the AI tier: `plugins-ai:ai-core`, `plugins-ai:providers`, `plugins-ai:prompts`
- **Profiles (plugins-core)**: May additionally depend on core rule modules (since they compose rules)

Cross-tier dependencies are forbidden. The CI guard (`tools/ci/verify-plugin-isolation.sh`) enforces this.
