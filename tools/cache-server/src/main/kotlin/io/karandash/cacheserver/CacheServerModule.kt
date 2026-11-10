package io.karandash.cacheserver

import io.karandash.cache.cas.CasDigest
import io.karandash.logger.Logger
import io.karandash.models.support.KarandashJson
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.flow.update
import kotlinx.io.readByteArray
import java.io.IOException
import java.security.MessageDigest

/**
 * The HTTP protocol of `HttpCacheBackend`: `GET`/`HEAD`/`PUT`/`DELETE` on `/{namespace...}/cas/{shard}/{remainder}`.
 *
 * `GET` answers 200 with the blob or 404; `HEAD` 200 or 404; `PUT` 201 once stored, 400 when the body's SHA-256 is
 * not the key, 413 over [CacheServerConfig.maxBlobBytes]; `DELETE` 204 or 404. With [CacheServerConfig.tokens] set,
 * every request but `GET /health` needs `Authorization: Bearer <token>` (401 otherwise). `GET /metrics` serves
 * [MetricsSnapshot] as JSON.
 */
fun Application.cacheServerModule(
    config: CacheServerConfig,
    store: BlobStore,
    metrics: CacheMetrics,
    logger: Logger,
) {
    val tokens = config.tokens.map { it.encodeToByteArray() }
    val handler = BlobHandler(config.maxBlobBytes, store, metrics, logger)
    routing {
        get("/health") { call.respondText("ok") }
        get("/metrics") {
            if (call.authorized(tokens)) {
                val body = KarandashJson.Default.encodeToString(MetricsSnapshot.serializer(), metrics.snapshot(store.size()))
                call.respondText(body, ContentType.Application.Json)
            }
        }
        route("{path...}") {
            get { blobRequest(tokens) { handler.get(call, it) } }
            head { blobRequest(tokens) { handler.head(call, it) } }
            put { blobRequest(tokens) { handler.put(call, it) } }
            delete { blobRequest(tokens) { handler.delete(call, it) } }
        }
    }
}

private suspend fun RoutingContext.blobRequest(
    tokens: List<ByteArray>,
    block: suspend (BlobKey) -> Unit,
) {
    if (!call.authorized(tokens)) return
    val key = parseKey(call.parameters.getAll("path").orEmpty())
    if (key == null) {
        call.respond(HttpStatusCode.BadRequest, "expected /{namespace...}/cas/{shard}/{remainder}")
    } else {
        block(key)
    }
}

/** `[namespace..., "cas", shard, remainder]`, where shard + remainder are a lowercase SHA-256 hex digest. */
internal fun parseKey(segments: List<String>): BlobKey? {
    if (segments.size < KEY_SEGMENTS || segments[segments.size - KEY_SEGMENTS] != CAS_SEGMENT) return null
    val hex = segments[segments.size - 2] + segments.last()
    if (segments[segments.size - 2].length != SHARD_LEN || !DIGEST_PATTERN.matches(hex)) return null
    return BlobKey(segments.dropLast(KEY_SEGMENTS).joinToString("/"), CasDigest(hex))
}

/** True when no tokens are configured or the request carries one of them; otherwise answers 401. */
private suspend fun ApplicationCall.authorized(tokens: List<ByteArray>): Boolean {
    if (tokens.isEmpty()) return true
    val presented = request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
        ?.substring(BEARER_PREFIX.length)
        ?.trim()
        ?.encodeToByteArray()
    // constant-time comparison: a token must not leak through response timing
    if (presented != null && tokens.any { MessageDigest.isEqual(it, presented) }) return true
    response.header(HttpHeaders.WWWAuthenticate, "Bearer")
    respond(HttpStatusCode.Unauthorized)
    return false
}

private class BlobHandler(
    private val maxBlobBytes: Long,
    private val store: BlobStore,
    private val metrics: CacheMetrics,
    private val logger: Logger,
) {
    suspend fun get(
        call: ApplicationCall,
        key: BlobKey,
    ) {
        val bytes = store.read(key)
        if (bytes == null) {
            metrics.misses.update { it + 1 }
            call.respond(HttpStatusCode.NotFound)
            return
        }
        metrics.hits.update { it + 1 }
        metrics.bytesRead.update { it + bytes.size }
        call.respondBytes(bytes, ContentType.Application.OctetStream)
    }

    suspend fun head(
        call: ApplicationCall,
        key: BlobKey,
    ) {
        call.respond(if (store.contains(key)) HttpStatusCode.OK else HttpStatusCode.NotFound)
    }

    suspend fun put(
        call: ApplicationCall,
        key: BlobKey,
    ) {
        if ((call.request.contentLength() ?: 0L) > maxBlobBytes) return reject(call, HttpStatusCode.PayloadTooLarge)
        // limit: the body is buffered (at most max-blob-size); stream to the temp file if blobs grow large
        val body = call.receiveChannel().readRemaining(maxBlobBytes + 1).readByteArray()
        if (body.size > maxBlobBytes) return reject(call, HttpStatusCode.PayloadTooLarge)
        if (CasDigest.of(body) != key.digest) return reject(call, HttpStatusCode.BadRequest)
        try {
            store.write(key, body)
        } catch (e: IOException) {
            logger.warn("Cache write failed for ${key.digest.hex.take(LOG_DIGEST_LEN)}: ${e.message}")
            call.respond(HttpStatusCode.InsufficientStorage)
            return
        }
        metrics.puts.update { it + 1 }
        metrics.bytesWritten.update { it + body.size }
        call.respond(HttpStatusCode.Created)
    }

    suspend fun delete(
        call: ApplicationCall,
        key: BlobKey,
    ) {
        call.respond(if (store.delete(key)) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
    }

    private suspend fun reject(
        call: ApplicationCall,
        status: HttpStatusCode,
    ) {
        metrics.rejected.update { it + 1 }
        call.respond(status)
    }
}

private const val CAS_SEGMENT = "cas"
private const val KEY_SEGMENTS = 3
private const val SHARD_LEN = 2
private const val LOG_DIGEST_LEN = 12
private const val BEARER_PREFIX = "Bearer "
private val DIGEST_PATTERN = Regex("[0-9a-f]{64}")
