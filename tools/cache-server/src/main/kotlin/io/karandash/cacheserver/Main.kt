package io.karandash.cacheserver

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.RawOption
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import io.karandash.logger.Logger
import io.karandash.platform.PlatformEnvironment
import io.karandash.size.KarandashSize
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.serialization.Serializable
import java.nio.file.Path
import kotlin.io.path.readLines

/**
 * Settings of one server. [tokens] are bearer tokens a client may present (none = no auth).
 * Never printed: [toString] shows only how many tokens are set.
 */
@Serializable
data class CacheServerConfig(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val dataDir: String = DEFAULT_DATA_DIR,
    val maxSizeBytes: Long = KarandashSize.ofGb(DEFAULT_MAX_SIZE_GB).bytes,
    val maxBlobBytes: Long = KarandashSize.ofMb(DEFAULT_MAX_BLOB_MB).bytes,
    val tokens: List<String> = emptyList(),
) {
    override fun toString(): String =
        "CacheServerConfig(host=$host, port=$port, dataDir=$dataDir, maxSizeBytes=$maxSizeBytes, " +
            "maxBlobBytes=$maxBlobBytes, tokens=${tokens.size})"

    companion object {
        const val DEFAULT_HOST = "0.0.0.0"
        const val DEFAULT_PORT = 5071
        const val DEFAULT_DATA_DIR = "karandash-cache"
        private const val DEFAULT_MAX_SIZE_GB = 10L
        private const val DEFAULT_MAX_BLOB_MB = 64L
    }
}

/** Writes every level to stderr, prefixed with the level. */
private object StderrLogger : Logger {
    override fun debug(message: String) = Unit

    override fun info(message: String) = PlatformEnvironment.stderrPrintln("INFO  $message")

    override fun warn(message: String) = PlatformEnvironment.stderrPrintln("WARN  $message")

    override fun error(message: String) = PlatformEnvironment.stderrPrintln("ERROR $message")
}

private class CacheServerCommand : CliktCommand(name = "karandash-cache-server") {
    private val host by option("--host", envvar = "KARANDASH_CACHE_HOST", help = "Bind address")
        .default(CacheServerConfig.DEFAULT_HOST)
    private val port by option("--port", envvar = "KARANDASH_CACHE_PORT", help = "Listen port")
        .int()
        .restrictTo(1..MAX_PORT)
        .default(CacheServerConfig.DEFAULT_PORT)
    private val dataDir by option("--data-dir", envvar = "KARANDASH_CACHE_DATA_DIR", help = "Blob directory")
        .default(CacheServerConfig.DEFAULT_DATA_DIR)
    private val maxSize by option("--max-size", envvar = "KARANDASH_CACHE_MAX_SIZE", help = "Total size before LRU eviction (e.g. 10gb)")
        .size()
    private val maxBlobSize by option(
        "--max-blob-size",
        envvar = "KARANDASH_CACHE_MAX_BLOB_SIZE",
        help = "Largest accepted PUT body (e.g. 64mb)"
    ).size()
    private val tokensFile by option(
        "--tokens-file",
        envvar = "KARANDASH_CACHE_TOKENS_FILE",
        help = "File with one bearer token per line (also: KARANDASH_CACHE_TOKENS, comma-separated)",
    )

    override fun run() {
        val defaults = CacheServerConfig()
        val config = CacheServerConfig(
            host = host,
            port = port,
            dataDir = dataDir,
            maxSizeBytes = maxSize ?: defaults.maxSizeBytes,
            maxBlobBytes = maxBlobSize ?: defaults.maxBlobBytes,
            tokens = tokens(),
        )
        if (config.maxBlobBytes > config.maxSizeBytes) throw UsageError("--max-blob-size must not exceed --max-size")
        serve(config, StderrLogger)
    }

    // tokens come from the environment or a file only: a command-line flag would show in `ps`
    private fun tokens(): List<String> {
        val fromEnv = PlatformEnvironment.getEnv("KARANDASH_CACHE_TOKENS").orEmpty().split(',')
        val fromFile = tokensFile?.let { Path.of(it).readLines() }.orEmpty()
        return (fromEnv + fromFile).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private companion object {
        const val MAX_PORT = 65535
    }
}

/** A size option such as `10gb`, in bytes. */
private fun RawOption.size() =
    convert { value ->
        try {
            KarandashSize.parse(value).bytes
        } catch (e: IllegalStateException) {
            fail(e.message ?: "invalid size: $value")
        }
    }

/** Runs the server until the JVM stops; SIGTERM drains in-flight requests for [GRACE_MS] before closing. */
fun serve(
    config: CacheServerConfig,
    logger: Logger,
) {
    val metrics = CacheMetrics()
    val store = BlobStore(Path.of(config.dataDir), config.maxSizeBytes, logger, metrics)
    val server = embeddedServer(CIO, port = config.port, host = config.host) {
        cacheServerModule(config, store, metrics, logger)
        monitor.subscribe(ApplicationStopped) { logger.info("Cache server stopped") }
    }
    Runtime.getRuntime().addShutdownHook(Thread { server.stop(GRACE_MS, TIMEOUT_MS) })
    logger.info("Cache server listening on ${config.host}:${config.port} ($config)")
    server.start(wait = true)
}

private const val GRACE_MS = 2_000L
private const val TIMEOUT_MS = 10_000L

fun main(args: Array<String>) = CacheServerCommand().main(args)
