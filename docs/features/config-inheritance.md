# Config Inheritance

## Overview

Karandash supports config inheritance via the `extends` directive, allowing you to build on shared base configurations. This enables teams to maintain common validation rules in a central location while allowing project-specific customizations.

## Supported Sources

The `extends` field accepts multiple source types:

### Single Extends (String)
- **HTTP/HTTPS URLs**: `extends: "https://example.com/base-config.yaml"`
- **Local files**: `extends: "./base-config.yaml"`
- **Relative paths**: `extends: "../shared/config.yaml"`

### Single Extends (Object with Auth)
```yaml
extends:
  url: "https://private.gitlab.com/config.yaml"
  auth:
    type: bearer-token
    token: "${GITLAB_TOKEN}"
```

### Multiple Extends (Array)
```yaml
extends:
  - "https://example.com/base-config.yaml"
  - "https://example.com/security-config.yaml"
  - url: "https://private.gitlab.com/team-config.yaml"
    auth:
      type: bearer-token
      token: "${GITLAB_TOKEN}"
```

**Array merging**: Configs are merged **left-to-right** - each subsequent entry overrides the previous. The child config overrides all parents. Maximum **10 entries** (error E0014).

All source types support YAML, JSON, and Kotlin DSL formats.

**Supported auth types**:
- `bearer-token`: `{ type: "bearer-token", token: "..." }`
- `basic-auth`: `{ type: "basic-auth", username: "...", password: "..." }`
- `custom-header`: `{ type: "custom-header", name: "X-Api-Key", value: "..." }`

HTTPS is required for all authenticated extends.

---

## Merge Behavior

Configuration merging follows these rules:

1. **Child overrides parent**: Values in the child config override parent values
2. **Explicit null clears**: `key: null` (or `~`) in the child clears the parent value, so the key falls back to
   its default. Leave the key out to keep the parent value
3. **Field-level merging**: Each top-level field is merged independently
4. **Array replacement**: By default, arrays in child replace parent arrays entirely

### Example

**Parent config:**
```yaml
rules:
  title-format:
    pattern: "^feat:"
  size-limits:
    max-files: 50
```

**Child config:**
```yaml
extends: "./parent.yaml"
rules:
  title-format:
    pattern: "^\\[HOTFIX\\]"
```

**Result**: Child's `title-format.pattern` wins; `size-limits` inherited from parent.

---

## Parallel HTTP Fetching

Remote configs are fetched in parallel instead of sequentially:

```yaml
extends:
  - "https://company.com/base.yaml"        # Fetched in parallel
  - "https://company.com/security.yaml"    # Fetched in parallel
  - "https://company.com/team.yaml"        # Fetched in parallel
```

For 3 remote configs with 200ms latency each:
- Sequential: 600ms total
- Parallel: **200ms total** (3x faster)

After all fetches complete, configs are merged left-to-right to preserve ordering.

---

## Glob Pattern Expansion

Local config files can be included using glob patterns:

```yaml
extends:
  - "configs/*.yaml"              # All YAML files in configs/
  - "configs/**/*.yaml"           # All YAML files recursively
  - "configs/team-?.yaml"         # team-a.yaml, team-b.yaml, etc.
  - "https://company.com/base.yaml"  # Mix with HTTP extends
```

| Pattern | Matches |
|---------|---------|
| `*` | Any single filename |
| `?` | Single character |
| `**` | Any directories (recursive) |
| `*.yaml` | All .yaml files |

Expanded files are sorted alphabetically for consistent merge order.

**Security constraints**: Max 10 files, max 5 directory levels, patterns must stay within the project directory (error E0020 on traversal).

---

## Conditional Extends

Extends can be conditionally applied based on environment variables or platform:

```yaml
extends:
  - url: "https://company.com/base.yaml"
  - url: "https://company.com/prod.yaml"
    if: "${ENV} == 'production'"
  - url: "https://company.com/dev.yaml"
    if: "${ENV} == 'development'"
```

### Condition expressions

```yaml
# Environment variables
if: "${ENV} == 'production'"
if: "${CI_COMMIT_BRANCH} == 'main'"

# Platform detection
if: "platform == 'gitlab'"
if: "platform == 'github'"

# Boolean operators
if: "${ENV} == 'prod' && ${REGION} == 'us-east-1'"
if: "${ENV} == 'dev' || ${ENV} == 'staging'"
if: "!${CI}"

# Parentheses
if: "(${ENV} == 'prod' && ${REGION} == 'us-east-1') || ${FORCE} == 'true'"
```

**Evaluation rules**: Conditions are evaluated before fetching (efficient). Missing variables are treated as empty string. False conditions silently skip the entry. Invalid expressions produce error E0016.

---

## Merge Strategies

Array fields can be merged using different strategies instead of the default replacement:

| Strategy | Behavior | Use Case |
|----------|----------|----------|
| `replace` | Child completely replaces parent (default) | Most fields |
| `append` | Child array added after parent array | Accumulate rules |
| `prepend` | Child array added before parent array | Override priority |
| `merge` | Union with duplicates removed | Combine patterns |

### Syntax

```yaml
extends:
  - url: "base.yaml"
    merge-strategy:
      rules.security.secret-patterns: append
      rules.labels.required-labels: merge
      rules.approval.required-approvers: replace
```

### Example: Append security patterns

Base config:
```yaml
rules:
  security:
    secret-patterns:
      - "PASSWORD_.*"
      - "API_KEY_.*"
```

Child config:
```yaml
extends:
  - url: "base.yaml"
    merge-strategy:
      rules.security.secret-patterns: append

rules:
  security:
    secret-patterns:
      - "CUSTOM_SECRET_.*"
```

Result:
```yaml
rules:
  security:
    secret-patterns:
      - "PASSWORD_.*"
      - "API_KEY_.*"
      - "CUSTOM_SECRET_.*"  # Appended, not replaced
```

### Supported fields

Merge strategies apply to array fields including: `rules.labels.required-labels`, `rules.labels.forbidden-labels`, `rules.security.secret-patterns`, `rules.security.sensitive-files`, `rules.file-changes.require-approval`, `rules.approval.required-approvers`, `rules.reviewer.required-reviewers`, `rules.reviewer.required-teams`, `rules.branch-naming.allowed-patterns`, `rules.cicd-status.required-jobs`, `rules.draft.wip-patterns`.

---

## Extends Manifest Files

Centralized extends management through manifest files. Define reusable extends chains and reference them with `@name` syntax.

### Create a manifest

Create `.karandash/extends-manifest.yaml` (or `.json`, `.kts` - any config format; keep only one):

```yaml
extends-sets:
  standard:
    - "https://company.com/base.yaml"
    - "https://company.com/security.yaml"

  strict:
    - "https://company.com/base.yaml"
    - "https://company.com/security.yaml"
    - "https://company.com/strict.yaml"

  production:
    - url: "https://company.com/base.yaml"
    - url: "https://company.com/prod-secure.yaml"
      auth:
        type: bearer-token
        token: "${PROD_TOKEN}"
```

### Use in project config

```yaml
extends: "@standard"
```

Mix manifest references with direct extends:

```yaml
extends:
  - "@standard"
  - "configs/team-overrides.yaml"
```

Apply conditions and merge strategies to manifest references:

```yaml
extends:
  - url: "@standard"
    if: "${ENV} != 'development'"
    merge-strategy:
      rules.security.secret-patterns: append
```

List available sets: `karandash validate-extends --list-sets`

---

## Extends Caching with TTL

Persistent caching of remote configs to avoid redundant fetches:

```yaml
extends:
  - url: "https://company.com/base.yaml"
    cache:
      ttl: 3600  # Cache for 1 hour
```

| Value | Behavior |
|-------|----------|
| `ttl: 3600` | Cache for 1 hour |
| `ttl: 86400` | Cache for 24 hours |
| `ttl: 0` | Never cache (always fetch) |
| `ttl: -1` | Cache forever (manual clear only) |

Caching uses HTTP ETags for conditional requests when cache expires. Cached configs live in the content-addressed cache under `~/.karandash/cache/` (`cas/` plus the `index/downloads/` namespace).

An entry without its own `cache.ttl` uses `cache.downloads.ttl` (default `1h`) of the config that names it, and a
parent's `cache.downloads` block carries on to the parents it extends in turn; `cache.downloads.enabled: false`
fetches every remote parent below it directly. Only `http(s)` parents are cached, never local files, and a cache read or
write that fails (`E0019`) is warned and the parent is fetched directly. Keys: [configuration schema](../reference/configuration-schema.md#cache).

**Performance**: For 3 remote extends, first run ~600ms, subsequent runs within TTL ~50ms (100x faster).

**Cache management**:
```bash
karandash cache clear            # Clear all cache
karandash cache clear-expired    # Clear only expired entries (alias of `cache gc`)
karandash cache info             # View cache stats (alias of `cache stats`)
```

Cache failures are warnings only (E0019) - validation continues normally.

---

## Extends Validation CLI

Pre-check extends chains before deploying configs:

```bash
karandash validate-extends                    # Validate auto-discovered config
karandash validate-extends --verbose          # Detailed output
karandash validate-extends --list-sets        # List manifest sets
```

Output example:
```
Extends Chain Validation
========================
  [OK]   https://company.com/base.yaml (fetched)
  [OK]   https://company.com/security.yaml (fetched)

Resolution:
  Total depth: 2 / 5 (max)
  Total extends: 2 / 19 (max)
  Circular references: none

Result: PASS (0 errors, 0 warnings)
```

See [validate-extends CLI reference](../reference/cli/validate-extends.md) for full options.

---

## Security

Karandash enforces strict security for config inheritance:

- **Path traversal protection**: Blocks `../../etc/passwd` patterns (error E0020)
- **HTTP timeouts**: 10 second request and connect timeout
- **Depth limiting**: Maximum 5 inheritance levels
- **Circular reference detection**: Prevents infinite loops (error E0010)
- **Array limit**: Maximum 10 entries in extends array (error E0014)
- **Glob boundaries**: Expanded paths must remain within the project directory

---

## Real-World Examples

### Monorepo with multiple teams

```yaml
# .karandash/extends-manifest.yaml
extends-sets:
  company-base:
    - "https://company.com/legal.yaml"
    - "https://company.com/security.yaml"

  frontend:
    - url: "@company-base"
    - "https://company.com/frontend-standards.yaml"

  backend:
    - url: "@company-base"
    - "https://company.com/backend-standards.yaml"
```

Team config:
```yaml
extends: "@frontend"

rules:
  title-format:
    pattern: "^(feat|fix|chore|docs):.*"
```

### Environment-specific with caching

```yaml
extends:
  - url: "@standard"
    cache:
      ttl: 86400  # 24h for stable base
  - url: "https://company.com/prod-strict.yaml"
    if: "${ENV} == 'production'"
    cache:
      ttl: 0  # Always fresh for production
  - url: "https://company.com/dev-lenient.yaml"
    if: "${ENV} == 'development'"
```

### Gradual feature rollout

```yaml
extends:
  - url: "@standard"
  - url: "strict-branch-naming.yaml"
    if: "${ENABLE_STRICT_BRANCHES} == 'true'"
  - url: "experimental-ai-review.yaml"
    if: "${BETA_FEATURES} == 'true'"
```

### Accumulating security patterns

```yaml
extends:
  - url: "@standard"
    merge-strategy:
      rules.security.secret-patterns: append
      rules.labels.required-labels: merge

rules:
  security:
    secret-patterns:
      - "COMPANY_SECRET_.*"
```

### Multi-level inheritance

**base.yaml** → **team.yaml** → **project.yaml**:
```yaml
# project.yaml
extends: "./team.yaml"
rules:
  title-format:
    pattern: "^\\[HOTFIX\\]"
```

Diamond inheritance (multiple parents extending same grandparent) is valid - the grandparent is cached and reused.

---

## Incremental Adoption

Enable features one at a time:

1. **Start**: Basic `extends: "base.yaml"`
2. **Add caching**: `cache: { ttl: 3600 }` on slow URLs
3. **Add conditions**: `if:` for environment-specific configs
4. **Add globs**: `*.yaml` patterns for local configs
5. **Create manifest**: `.karandash/extends-manifest.yaml` for large teams

---

## Error Codes

| Code | Issue | Solution |
|------|-------|----------|
| **E0009** | Fetch failure or path traversal blocked | Check URL is reachable; local files must stay under the project root (git top-level or the directory holding `.karandash/`) |
| **E0010** | Circular inheritance detected | Remove one of the circular references |
| **E0011** | Depth limit exceeded (max 5) | Reduce inheritance chain depth |
| **E0013** | Authentication failed | Verify token is set and HTTPS is used |
| **E0014** | Too many extends entries (max 10) | Consolidate extends or use manifests |
| **E0015** | Glob expansion failed | Check pattern syntax, ensure directory exists |
| **E0016** | Condition evaluation failed | Fix `if:` syntax (missing `${}` or invalid operator) |
| **E0017** | Extends manifest not found | Create `.karandash/extends-manifest.yaml` |
| **E0018** | Manifest set not found | Check set name, use `--list-sets` |
| **E0019** | Cache operation failed (warning) | Check cache directory permissions |
| **E0020** | Glob security violation | Pattern attempts path traversal |

See [Error Codes Reference](../reference/error-codes.md) for detailed troubleshooting.

## Best Practices

1. **Use HTTPS for remote configs** to prevent MITM attacks
2. **Version your base configs** with tags or commit hashes for reproducibility
3. **Keep inheritance shallow** - limit to 2-3 levels
4. **Enable caching** for stable remote configs
5. **Use manifests** for organization-wide governance
6. **Test extends** with `karandash validate-extends --verbose` before deploying
