# simulate

Simulate approval policy evaluation against MR state.

## Overview

The `simulate` command loads your config the way `validate` does and runs only the `approval` rule
(including its `policy:` tree) on the MR at `--mr-url`, or on a synthetic MR with no approvals when no URL
is given. `--approvers` adds hypothetical approvals on top. It prints each finding and whether the policy
is satisfied, without posting comments or modifying any MR. It exits 1 when the policy blocks the MR, and
prints a note (exit 0) when the config has no `rules.approval` block.

This is particularly useful when iterating on policy configuration or debugging why an MR is blocked.

## Synopsis

```
karandash simulate [--mr-url <url>] [--approvers <list>] [--config <path>]
```

## Options

| Flag | Short | Type | Default | Description |
|------|-------|------|---------|-------------|
| `--mr-url` | -- | `String` | synthetic MR | MR/PR URL to simulate against (fetched with the platform configured under `platforms:`) |
| `--approvers` | -- | `String` | -- | Comma-separated list of hypothetical approvers |
| `--config` | `-f` | `String` | auto-discover | Config file path |

## Usage Examples

```bash
# Would a synthetic MR with no approvals pass?
karandash simulate

# Simulate against a specific MR URL
karandash simulate --mr-url https://github.com/owner/repo/pull/42

# Simulate with hypothetical approvers to see if approval would pass
karandash simulate --approvers "alice,bob"

# Simulate with a specific config file
karandash simulate --config .karandash/strict.yaml --mr-url https://gitlab.com/group/repo/-/merge_requests/7
```

## Configuration

Approval policies are configured in your config file under `rules.approval`:

```yaml
rules:
  approval:
    minApprovals: 2
    noVeto: true
    noSelfApproval: true
```

## See Also

- [verify-policy](verify-policy.md) - verify policy configuration for logical consistency
- [validate](validate.md) - run the full validation pipeline
- [Configuration Schema](../configuration-schema.md) - approval policy configuration
