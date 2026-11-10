# cache

Manage the local and remote CAS (content-addressable storage) cache.

## Overview

Karandash maintains a local CAS blob store to cache validation results and provider responses. The `cache` command provides subcommands to inspect and clean the local cache, push local blobs to a remote backend and inspect remote state. Remote `extends:` configs are cached here too (`index/downloads/`).

During `validate`, a `cache.remote.url` must be a public HTTPS URL unless the run sets `allow-localhost: true`
(or `--allow-localhost`), which also permits a loopback or private backend over HTTP; a rejected URL is warned
and the run continues with the local cache only.

Backends: `http`, `s3` and `gcs`. `gcs` is not a separate client: it is the `http` backend, so `url` is the bucket's
HTTP endpoint and the credential goes in `cache.remote.auth`, sent as the `Authorization` header (as is when it
names a scheme, otherwise as `Bearer <auth>`). Every `cache:` and `cache.remote` key, with its default, is in
[Configuration schema: Cache](../configuration-schema.md#cache).

## Subcommands

| Subcommand | Description |
|------------|-------------|
| `stats` (alias `info`) | Show local blob count, total size, entries per index namespace and category, orphaned blobs and the last run's hits and misses |
| `gc` (alias `clear-expired`) | Remove expired index entries and orphaned blobs, report bytes reclaimed |
| `clear` | Delete all local cache data (blobs, index entries and the last run's stats) |
| `push` | Upload local CAS blobs to a remote cache backend |
| `remote-stats` | Show remote backend configuration and local blob counts |

---

## `karandash cache stats` / `cache gc` / `cache clear`

```
karandash cache stats [--cache-dir <dir>]    # alias: cache info
karandash cache gc [--cache-dir <dir>]       # alias: cache clear-expired
karandash cache clear [--cache-dir <dir>]
```

| Flag | Short | Type | Default | Description |
|------|-------|------|---------|-------------|
| `--cache-dir` | -- | `String` | `~/.karandash/cache` | Local cache base directory |

Each exits 0 on success and 6 when a cache path cannot be read or deleted (the summary is still printed).

`stats` names the category each index namespace holds (`http-responses (provider-responses)`, `responses (ai-responses)`,
`state (validation-state)`, `downloads (extends, plugins)`) and, when a `validate` run has saved them to
`last-run-stats.json`, prints that run's hits, misses and hit rate per cache, plus the remote layer's deduplicated writes.
The `rules` cache tier is `MEMORY_AND_FILE`: rule results persist under the cache dir (state TTL) and a later `validate` of an unchanged MR, config and enrichment reuses them. `validate --no-cache` neither reads nor uses it; `--invalidate-rules <ids>` keeps it and recomputes only the named rules, writing their fresh results back; `--gate-on-cached` gates on findings an extension served from its incremental cache, after the same `karandash-disable` and baseline suppression as fresh findings. `clear` invalidates every namespace (listed by category with its entry count), then deletes the blobs, index files and
`last-run-stats.json`.

---

## `karandash cache push`

Upload all local CAS blobs to a remote cache backend. Skips blobs already present on the remote and blobs that exceed the configured maximum blob size.

### Synopsis

```
karandash cache push [--backend-url <url>] [--backend-type <type>] [--config <file>] [--cache-dir <dir>] [--allow-localhost]
```

### Options

| Flag | Short | Type | Default | Description |
|------|-------|------|---------|-------------|
| `--backend-url` | -- | `String` | `cache.remote.url` | Remote backend URL; required when the config sets no `cache.remote.url` |
| `--backend-type` | -- | `String` | `cache.remote.backend`, else `http` | Backend type: `http`, `s3`, or `gcs` (`gcs` uses the `http` backend) |
| `--config` | `-c` | `String` | discovered | Config file whose `cache.remote` block supplies the URL, backend, `auth` and S3 credentials |
| `--cache-dir` | -- | `String` | `~/.karandash/cache` | Local cache base directory |
| `--allow-localhost` | -- | flag | off | Allow a loopback or private-IP backend over HTTP (disables SSRF protection); `allow-localhost: true` in the config does the same |

`cache push` reads the same `cache.remote` block as `validate`, so both send the same `auth` header and the same S3
credentials. Flags override the config's URL and backend. A config that fails to load exits 2, as does a push with no
URL from either source.

Without `--allow-localhost`, SSRF protection blocks a `localhost`, `127.0.0.1` or private-network backend URL and
every blob counts as failed (each failure is logged with its reason). A blob the backend refuses (non-2xx) also counts
as failed, and any failed blob makes the push exit 6.

### S3 credentials

An `s3` backend signs every request with AWS Signature V4 when `cache.remote` has both `access-key-id` and
`secret-access-key`. Without them it falls back to the standard AWS chain: `cache.remote.profile` (that shared-file
profile only), else `AWS_ACCESS_KEY_ID` + `AWS_SECRET_ACCESS_KEY` (+ `AWS_SESSION_TOKEN`), else the `AWS_PROFILE` /
`default` profile in `~/.aws/credentials` or `~/.aws/config` (see [AWS CodeCommit](../../platforms/aws-codecommit.md#credential-resolution)
for the full order). With none of them it sends anonymous requests, which only a bucket whose policy allows them
accepts. Signing replaces `auth` for S3. Without `cache.remote.region` the chain's region applies (`AWS_REGION` >
`AWS_DEFAULT_REGION` > the profile's `region` > `us-east-1`).

The chain (env keys and `~/.aws`, not keys written in the config) is used only when `cache.remote.url` is `https` to
an AWS host (`*.amazonaws.com`, `*.amazonaws.com.cn`); S3-compatible stores (MinIO, Cloudflare R2, …) need
`access-key-id` + `secret-access-key` in the config. `cache.remote` (url, profile, region) inherited from an `extends:`
parent works exactly like the root's own: the chain signs for it, so extend only configs you trust.

Signed S3 requests do not follow HTTP redirects.

```yaml
cache:
  remote:
    backend: s3
    url: https://my-bucket.s3.eu-west-1.amazonaws.com
    region: eu-west-1                                    # default us-east-1
    access-key-id: ${KARANDASH_S3_ACCESS_KEY_ID}
    secret-access-key: ${KARANDASH_S3_SECRET_ACCESS_KEY}
    session-token: ${KARANDASH_S3_SESSION_TOKEN}         # optional, for temporary credentials
```

`AWS_SECRET_ACCESS_KEY` and `AWS_SESSION_TOKEN` are on the env denylist and cannot be interpolated; leave the keys
out and the chain reads them, or export the values under other names. `cache push --backend-url` with a URL other than
`cache.remote.url` (or with no `cache.remote` at all) sends neither the config's keys nor the chain's. The secret and session token are never logged or printed by `config describe`.

### Usage Examples

```bash
# Push to an HTTP backend
karandash cache push --backend-url https://cache.example.com

# Push to S3
karandash cache push \
  --backend-type s3 \
  --backend-url https://my-bucket.s3.amazonaws.com

# Push from a non-default cache directory
karandash cache push \
  --backend-url https://cache.example.com \
  --cache-dir /var/cache/karandash

# Push to a backend on this machine
karandash cache push --backend-url http://localhost:8080/cache --allow-localhost
```

Output on completion:

```
Cache push complete: 42 pushed, 8 skipped, 0 failed
```

---

## `karandash cache remote-stats`

Print remote backend configuration and local blob store statistics (blob count and total size).

### Synopsis

```
karandash cache remote-stats [--backend-type <type>] [--backend-url <url>] [--cache-dir <dir>]
```

### Options

| Flag | Short | Type | Default | Description |
|------|-------|------|---------|-------------|
| `--backend-type` | -- | `String` | `none` | Remote backend type: `http`, `s3`, `gcs`, or `none` |
| `--backend-url` | -- | `String` | -- | Remote backend URL |
| `--cache-dir` | -- | `String` | `~/.karandash/cache` | Local cache base directory |

### Usage Examples

```bash
# Show local stats only
karandash cache remote-stats

# Show stats with remote backend info
karandash cache remote-stats \
  --backend-type http \
  --backend-url https://cache.example.com
```

Example output:

```
Remote Cache Configuration
  Backend type : http
  Backend URL  : https://cache.example.com

Local Blob Store
  Base dir     : ~/.karandash/cache/cas/blobs
  Blobs        : 127
  Total size   : 4 MB
```

## See Also

- [Configuration Schema](../configuration-schema.md) - `cache` config block
- [config describe](config.md) - describe the `cache` config key
