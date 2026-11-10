# Config Inheritance Examples

This directory demonstrates how to use Karandash's config inheritance feature to build maintainable validation configurations.

## Overview

The examples show three common patterns:

1. **Base config**: Shared rules used across multiple projects
2. **Strict config**: Extends base with stricter validation
3. **Lenient config**: Extends base with relaxed validation

## Files

- `base-config.yaml` - Common validation rules for all projects
- `strict-config.yaml` - Stricter rules for production projects
- `lenient-config.yaml` - Relaxed rules for experimental projects

## Usage

### Using Base Config

```bash
karandash validate --config examples/config-inheritance/base-config.yaml
```

### Using Strict Config

The strict config extends the base and adds stricter limits:

```bash
karandash validate --config examples/config-inheritance/strict-config.yaml
```

### Using Lenient Config

The lenient config extends the base with relaxed rules:

```bash
karandash validate --config examples/config-inheritance/lenient-config.yaml
```

## Inheritance Chain

```
base-config.yaml
    ├── strict-config.yaml (stricter limits)
    └── lenient-config.yaml (relaxed limits)
```

## Testing Inheritance

You can test how configs merge by running:

```bash
# Test with base config
echo "feat: add new feature" | karandash validate --config base-config.yaml --stdin

# Test with strict config (shorter title limit)
echo "feat: add new feature with a very long title that exceeds limits" | karandash validate --config strict-config.yaml --stdin

# Test with lenient config (longer title allowed)
echo "feat: add new feature with a very long title that would normally exceed limits" | karandash validate --config lenient-config.yaml --stdin
```

## Remote Base Config Pattern

In production, you'd typically host the base config remotely:

```yaml
# Your project's .karandash.yaml
extends: "https://raw.githubusercontent.com/your-org/configs/main/base-mr-validation.yaml"

# Project-specific overrides
maxTitleLength: 80
```

## Key Learnings

1. **Inheritance reduces duplication**: Define common rules once in base config
2. **Override only what changes**: Child configs only specify differences
3. **Arrays are replaced**: If child defines `rules`, it replaces parent's `rules` entirely
4. **Null values are ignored**: Setting a field to `null` in child keeps parent's value

## Merge Behavior Example

Given:

**base-config.yaml:**
```yaml
maxTitleLength: 72
allowedTypes:
  - feat
  - fix
```

**child-config.yaml:**
```yaml
extends: "./base-config.yaml"
maxTitleLength: 50
```

**Resolved config:**
```yaml
maxTitleLength: 50  # Overridden from child
allowedTypes:       # Inherited from parent
  - feat
  - fix
```

## Error Handling

The examples also demonstrate error handling:

```bash
# E0009: Invalid path traversal
extends: "../../etc/passwd"

# E0010: Circular reference
# base.yaml extends strict.yaml
# strict.yaml extends base.yaml

# E0011: Too deep (max 5 levels)
# A → B → C → D → E → F (6 levels)
```

## Next Steps

1. Copy one of these examples as a starting point
2. Customize rules for your project
3. Host base config in version control
4. Reference base config via HTTPS URL
5. Test inheritance chain with real commit messages
