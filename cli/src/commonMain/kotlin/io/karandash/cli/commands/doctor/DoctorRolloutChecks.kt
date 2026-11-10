package io.karandash.cli.commands.doctor

import io.karandash.api.support.suspendCatching
import io.karandash.cache.CacheManager
import io.karandash.cache.cas.CasDigest
import io.karandash.cli.cache.connectRemoteCache
import io.karandash.cli.commands.cache.expandCacheDir
import io.karandash.cli.pipeline.PipelineInfrastructure
import io.karandash.config.Config
import io.karandash.crypto.AwsCredentialChain
import io.karandash.duration.KarandashDuration
import io.karandash.http.ProviderHttpClientFactory
import io.karandash.http.SsrfUrlValidator
import io.karandash.logger.Logger
import io.karandash.platform.PlatformEnvironment
import io.karandash.platform.createDirectories
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Rollout checks of `karandash doctor`, run on a config that loaded: the `extends:` chain, the secrets the config references (names only, never values), the local cache directory,
 * the remote cache and the AI endpoint. A network probe is skipped with [offline]. [env] and [engine] are for tests.
 */
internal class DoctorRolloutChecks(
    private val logger: Logger,
    private val offline: Boolean,
    private val env: (String) -> String? = PlatformEnvironment::getEnv,
    private val engine: HttpClientEngine? = null,
) {
    /** Each section title with its results, in output order. */
    suspend fun run(pass: DoctorCheckResult.Pass): List<Pair<String, List<DoctorCheckResult>>> {
        val config = pass.config ?: Config()
        return listOf(
            "Extends Chain" to extendsChain(pass.configLayers.map { it.first }),
            "Tokens" to tokens(pass.configLayers.map { it.second }),
            "Cache" to listOfNotNull(cacheDir(config), remoteCache(config, pass.configLayers)),
            "AI Endpoint" to listOf(aiEndpoint(config)),
        )
    }

    /** The chain, root last. */
    fun extendsChain(sources: List<String>): List<DoctorCheckResult> {
        if (sources.size < 2) return listOf(DoctorCheckResult.Pass("No extends: chain (single config file)"))
        return listOf(DoctorCheckResult.Pass("${sources.size} layer(s): ${sources.joinToString(" -> ")}"))
    }

    /** `${NAME}` references in the config layers whose name looks like a secret: set or missing, value never shown. */
    fun tokens(layers: List<JsonElement>): List<DoctorCheckResult> {
        val names = layers
            .flatMap(::strings)
            .flatMap { text -> ENV_REFERENCE.findAll(text).map { it.groupValues[1] } }
            .filter { name -> SECRET_MARKERS.any { it in name } }
            .distinct()
            .sorted()
        if (names.isEmpty()) return listOf(DoctorCheckResult.Pass("The config references no token variables"))
        return names.map { name ->
            if (env(name).isNullOrEmpty()) {
                DoctorCheckResult.Warn("$name: missing", hint = "Set $name in the CI variables (masked) or the shell environment.")
            } else {
                DoctorCheckResult.Pass("$name: set")
            }
        }
    }

    /** Writes, reads back and deletes a probe file in the cache directory; fails when the run could not cache. */
    fun cacheDir(config: Config): DoctorCheckResult {
        val cache = config.cache
        if (cache?.enabled == false) return DoctorCheckResult.Pass("Cache disabled (cache.enabled: false)")
        val dir = expandCacheDir(cache?.dir ?: CacheManager.DEFAULT_BASE_DIR)
        val probe = Path(dir, PROBE_FILE)
        return suspendCatching {
            createDirectories(dir)
            SystemFileSystem.sink(probe).buffered().use { it.writeString(PROBE_TEXT) }
            val read = SystemFileSystem.source(probe).buffered().use { it.readString() }
            SystemFileSystem.delete(probe)
            check(read == PROBE_TEXT) { "read back differs from what was written" }
        }.fold(
            { DoctorCheckResult.Pass("Cache directory $dir is readable and writable") },
            {
                DoctorCheckResult.Fail(
                    "Cache directory $dir is not writable: ${it.message}",
                    hint = "Point cache.dir at a writable directory (in CI, one inside the project so the runner cache keeps it).",
                )
            },
        )
    }

    /** One HEAD request for the empty blob when `cache.remote` is set; any HTTP answer counts as reachable. */
    suspend fun remoteCache(
        config: Config,
        layers: List<Pair<String, JsonElement>>,
    ): DoctorCheckResult? {
        val remote = config.cache?.remote ?: return null
        if (offline) return DoctorCheckResult.Pass("Remote cache ${remote.backend} configured (not probed: --offline)")
        val allowLocalhost = config.allowLocalhost
        return suspendCatching {
            connectRemoteCache(
                remote,
                logger,
                allowLocalhost,
                AwsCredentialChain(logger, env),
                PipelineInfrastructure.remoteRegionSet(layers),
                engine
            ).use { it.exists(CasDigest.of(ByteArray(0))) }
        }.fold(
            { DoctorCheckResult.Pass("Remote cache ${remote.backend} reachable") },
            {
                DoctorCheckResult.Fail(
                    "Remote cache ${remote.backend} unreachable: ${it.message}",
                    hint = "Check cache.remote.url and its credentials; runs fall back to the local cache meanwhile.",
                )
            },
        )
    }

    /** A GET of `ai.base-url` within [AI_PROBE_SECONDS]: any HTTP status counts as reachable (no API key is sent). */
    suspend fun aiEndpoint(config: Config): DoctorCheckResult {
        val ai = config.ai as? JsonObject ?: return DoctorCheckResult.Pass("AI not configured or disabled (ai.enabled: false)")
        val baseUrl = (ai[BASE_URL_KEY] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return DoctorCheckResult.Pass("AI configured without ai.base-url (provider default endpoint, not probed)")
        if ("\${" in baseUrl) {
            return DoctorCheckResult.Warn(
                "ai.base-url reads a variable that is not set; the AI endpoint was not probed",
                hint = "Set the variable ai.base-url reads, or write the URL in the config.",
            )
        }
        if (offline) return DoctorCheckResult.Pass("AI endpoint $baseUrl configured (not probed: --offline)")
        val result = suspendCatching {
            SsrfUrlValidator.validate(baseUrl, config.allowLocalhost, config.allowPrivateNetwork)
            val client = ProviderHttpClientFactory.create(
                engine = engine,
                timeout = KarandashDuration.ofSeconds(AI_PROBE_SECONDS),
                logger = logger,
                allowLocalhost = config.allowLocalhost,
            )
            try {
                client.get(baseUrl).status.value
            } finally {
                client.close()
            }
        }
        return result.fold(
            { DoctorCheckResult.Pass("AI endpoint $baseUrl answered (HTTP $it)") },
            {
                DoctorCheckResult.Fail(
                    "AI endpoint $baseUrl unreachable within ${AI_PROBE_SECONDS}s: ${it.message}",
                    hint = "Check ai.base-url and the network from this runner; AI rules are skipped while it is down.",
                )
            },
        )
    }

    private fun strings(element: JsonElement): List<String> =
        when (element) {
            is JsonPrimitive -> listOfNotNull(element.contentOrNull)
            is JsonArray -> element.flatMap(::strings)
            is JsonObject -> element.values.flatMap(::strings)
        }

    private companion object {
        const val AI_PROBE_SECONDS = 5L
        const val BASE_URL_KEY = "baseUrl"
        const val PROBE_FILE = ".doctor-probe"
        const val PROBE_TEXT = "karandash doctor"
        val SECRET_MARKERS = listOf("TOKEN", "KEY", "SECRET", "PASSWORD")
        val ENV_REFERENCE = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)""")
    }
}
