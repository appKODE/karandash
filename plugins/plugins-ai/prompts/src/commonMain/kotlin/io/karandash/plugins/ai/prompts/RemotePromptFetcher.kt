package io.karandash.plugins.ai.prompts

import io.karandash.api.support.suspendCatching
import io.karandash.crypto.sha256Hex
import io.karandash.duration.KarandashDuration
import io.karandash.http.ProviderHttpClientFactory
import io.karandash.sdk.Logger
import io.karandash.utils.BoundedCache
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray

/**
 * Fetches AI prompts from remote URLs with caching, size limits, and security checks.
 *
 * Only HTTPS URLs are allowed. Responses are cached in memory for [cacheTtl], keyed on the whole source
 * (URL, format, section and pin), so a cached text never skips another source's SHA-256 check.
 * TOML format is supported for extracting specific sections; a missing section is an error.
 * The body is read up to the size limit and never held whole beyond it. The fetcher owns [httpClient]: [close] closes it.
 *
 * @param logger Optional logger for verbose diagnostics. Uses [Logger.debug] for cache hits/misses
 *               and fetch outcomes so output only appears with `--verbose`.
 */
class RemotePromptFetcher(
    private val httpClient: HttpClient,
    private val cacheTtl: KarandashDuration = KarandashDuration.ofHours(1),
    private val maxResponseChars: Int = 102_400,
    private val logger: Logger? = null,
) {
    /** A fetcher owning a [ProviderHttpClientFactory] client (SSRF-checked; [allowLocalhost] for tests). */
    constructor(logger: Logger, allowLocalhost: Boolean) : this(
        httpClient = ProviderHttpClientFactory.create(logger = logger, allowLocalhost = allowLocalhost),
        logger = logger,
    )

    private companion object {
        private const val HTTP_SUCCESS_MIN = 200
        private const val HTTP_SUCCESS_MAX = 299
        private const val MAX_PROMPT_CACHE_ENTRIES = 64

        /** UTF-8 bytes per UTF-16 char at most (a 4-byte sequence is a surrogate pair). */
        private const val MAX_UTF8_BYTES_PER_CHAR = 3
        private val TOML_TRIPLE_QUOTE_REGEX = Regex("""=\s*"{3}([\s\S]*?)"{3}""")
        private val TOML_SINGLE_QUOTE_REGEX = Regex("""=\s*"(.*?)"""")
    }

    private val cache = BoundedCache<PromptSource.Url, String>(
        maxEntries = MAX_PROMPT_CACHE_ENTRIES,
        ttl = cacheTtl.toKotlinDuration(),
    )

    /**
     * Fetches prompt text from [source], applying caching, format extraction, and integrity checks.
     *
     * @param source The URL prompt source configuration
     * @return [Result] containing the resolved prompt text or an error
     */
    suspend fun fetch(source: PromptSource.Url): Result<String> =
        suspendCatching {
            require(source.url.startsWith("https://")) {
                "Remote prompt URLs must use HTTPS: ${source.url}"
            }

            // keyed on the whole source, so a hit was verified against this source's own pin and section.
            cache.get(source)?.let { text ->
                logger?.debug("Prompt fetch: cache hit for ${source.url}")
                return@suspendCatching text
            }

            logger?.debug("Prompt fetch: downloading ${source.url} (format=${source.format})")
            val raw = downloadText(source.url)
            val text = when (source.format.lowercase()) {
                "toml" -> {
                    logger?.debug("Prompt fetch: extracting TOML section '${source.section}' from ${source.url}")
                    extractTomlSection(raw, source.section)
                }

                else -> {
                    raw
                }
            }

            source.sha256?.let { expectedHash ->
                val actualHash = sha256Hex(text)
                require(actualHash.equals(expectedHash, ignoreCase = true)) {
                    "SHA-256 mismatch for ${source.url}: expected=$expectedHash, actual=$actualHash"
                }
                logger?.debug("Prompt fetch: SHA-256 verified for ${source.url}")
            }

            cache.put(source, text)
            logger?.debug("Prompt fetch: cached ${text.length} chars from ${source.url}")
            text
        }

    /** Closes the HTTP client; idempotent. */
    fun close() {
        httpClient.close()
    }

    private suspend fun downloadText(url: String): String =
        httpClient.prepareGet(url).execute { response ->
            require(response.status.value in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
                "Failed to fetch prompt from $url: HTTP ${response.status.value}"
            }
            // read one byte past the largest body the char limit allows, never the whole stream.
            val maxBytes = maxResponseChars.toLong() * MAX_UTF8_BYTES_PER_CHAR
            val bytes = response.bodyAsChannel().readBuffer(maxBytes + 1).readByteArray()
            require(bytes.size <= maxBytes) { "Remote prompt response too large (over $maxBytes bytes, limit $maxResponseChars chars)" }
            val body = bytes.decodeToString()
            require(body.length <= maxResponseChars) {
                "Remote prompt response too large (${body.length} chars, limit $maxResponseChars)"
            }
            body
        }

    internal fun extractTomlSection(
        toml: String,
        section: String?
    ): String {
        if (section == null) return toml
        val lines = toml.lines()
        val sectionHeader = "[$section]"
        val startIdx = lines.indexOfFirst { it.trim() == sectionHeader }
        require(startIdx >= 0) { "TOML section [$section] not found" }

        val endIdx = lines.drop(startIdx + 1).indexOfFirst { it.trimStart().startsWith("[") }
        val sectionLines = if (endIdx < 0) {
            lines.drop(startIdx + 1)
        } else {
            lines.drop(startIdx + 1).take(endIdx)
        }

        val content = sectionLines.joinToString("\n").trim()
        val tripleQuoteMatch = TOML_TRIPLE_QUOTE_REGEX.find(content)
        if (tripleQuoteMatch != null) return tripleQuoteMatch.groupValues[1].trimIndent()
        val singleQuoteMatch = TOML_SINGLE_QUOTE_REGEX.find(content)
        if (singleQuoteMatch != null) return singleQuoteMatch.groupValues[1]
        return content
    }
}
