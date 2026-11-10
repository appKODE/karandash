# Self-hosted cache server

`karandash-cache-server` is an optional remote cache you run yourself. It speaks the protocol of the `http` remote
cache backend, so the existing `cache.remote` block points at it with no client change. A typical setup runs it on
the same host as the CI runners, so runners that do not share a local disk still share cached blobs (AI analysis
results, provider responses).

Nothing uses it unless a config sets `cache.remote`. Only blobs go to the server; each runner keeps its own cache
index (`index/`), so a blob is pulled when the runner's index points at a blob its local store does not have.

## Run it

Build the jar and the image (the image takes the pre-built jar, like the other karandash images):

```bash
./gradlew :tools:cache-server:shadowJar
docker build -f docker/Dockerfile.cache-server -t karandash/cache-server .
```

```bash
docker run -d --name karandash-cache --restart unless-stopped \
  -p 5071:5071 \
  -v karandash-cache:/data \
  -e KARANDASH_CACHE_TOKENS="$(openssl rand -hex 32)" \
  -e KARANDASH_CACHE_MAX_SIZE=20GB \
  karandash/cache-server
```

docker compose:

```yaml
services:
  karandash-cache:
    image: karandash/cache-server
    restart: unless-stopped
    ports:
      - "5071:5071"
    volumes:
      - karandash-cache:/data
    environment:
      KARANDASH_CACHE_TOKENS: ${KARANDASH_CACHE_TOKENS}   # comma-separated, one per team or project
      KARANDASH_CACHE_MAX_SIZE: 20GB
    healthcheck:
      test: ["CMD-SHELL", "exec 3<>/dev/tcp/127.0.0.1/5071 && printf 'GET /health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q ok <&3"]
      interval: 30s
volumes:
  karandash-cache:
```

Without Docker: `java -jar tools/cache-server/build/libs/karandash-cache-server.jar --data-dir /var/lib/karandash-cache`.

## Point karandash at it

```yaml
allow-localhost: true            # needed for an http:// URL, see Security below
cache:
  remote:
    backend: http
    url: http://10.0.0.5:5071    # the cache host's LAN address
    prefix: android/my-app       # namespace: one per project sharing the server
    auth: ${KARANDASH_CACHE_TOKEN}
```

`auth` is sent as `Authorization: Bearer <token>`. `prefix` keeps projects apart: the same blob under two prefixes is
stored twice and never seen across them. `karandash cache push` and `cache remote-stats` read the same block.

## Settings

Each setting is a flag or an environment variable; the flag wins.

| Flag | Environment | Default | Meaning |
|------|-------------|---------|---------|
| `--host` | `KARANDASH_CACHE_HOST` | `0.0.0.0` | Bind address |
| `--port` | `KARANDASH_CACHE_PORT` | `5071` | Port |
| `--data-dir` | `KARANDASH_CACHE_DATA_DIR` | `karandash-cache` (`/data` in the image) | Blob directory |
| `--max-size` | `KARANDASH_CACHE_MAX_SIZE` | `10GB` | Total size; least recently used blobs are evicted above it |
| `--max-blob-size` | `KARANDASH_CACHE_MAX_BLOB_SIZE` | `64MB` | Largest accepted upload; larger ones get `413` |
| `--tokens-file` | `KARANDASH_CACHE_TOKENS_FILE` | none | File with one accepted token per line |
| none | `KARANDASH_CACHE_TOKENS` | none | Accepted tokens, comma-separated |

Tokens are never taken from a flag (it would show in `ps`) and never logged. With no token configured the server
accepts every request.

## Endpoints

| Request | Answer |
|---------|--------|
| `GET /health` | `200 ok`, no token needed |
| `GET /metrics` | JSON counters: `hits`, `misses`, `puts`, `bytesWritten`, `bytesRead`, `storedBytes`, `evictions`, `rejected` |
| `HEAD`/`GET`/`PUT`/`DELETE /{prefix}/cas/{2 hex}/{62 hex}` | The blob whose SHA-256 is the 64 hex digits; `404` when missing, `201` on store, `204` on delete |

A `PUT` whose body does not hash to its key is refused with `400`, so a client cannot store content under a wrong
digest. Writes go to a temporary file and are renamed into place, so a reader sees the old blob or the new one, never
a partial one. On `docker stop` the server stops accepting connections and finishes in-flight requests first.

## Security

- **Set a token.** Without one anyone who reaches the port can read and overwrite cached results. Use one token per
  team so a leaked one can be rotated alone (restart the server with the new list).
- **No TLS.** The server speaks plain HTTP. Keep it on a private network or the runner host, or put a reverse proxy
  with TLS (Caddy, nginx) in front.
- **SSRF settings on the client.** karandash refuses an `http://` remote cache URL unless the run sets
  `allow-localhost: true`, which also lifts the loopback, private and metadata checks for every outbound request of
  that run. Behind an HTTPS proxy on a private address, `allow-private-network: true` is enough and keeps the other
  checks. A refused URL is warned and the run continues with the local cache only.
- **Cached content is trusted by the reader.** A token holder can push any blob whose digest matches its content, but
  cannot replace what another key points at; the cache index that maps keys to digests stays on each runner.
