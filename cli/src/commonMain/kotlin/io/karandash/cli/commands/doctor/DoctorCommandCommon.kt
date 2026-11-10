package io.karandash.cli.commands.doctor

import io.karandash.api.exception.KarandashException
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.support.rethrowCancellation
import io.karandash.config.Config
import io.karandash.normalize.ModuleIdNormalizer
import io.karandash.patterns.PomskyConfigCheck
import io.karandash.sdk.PluginContext
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Result of a single health check.
 *
 * Shared between JVM and Native DoctorCommand implementations.
 */
sealed class DoctorCheckResult(
    val message: String
) {
    /**
     * Check passed.
     *
     * @param config Parsed [Config], available for downstream checks.
     * @param rawConfig The config merged over its `extends:` chain (as `validate` uses it), for policy validation.
     * @param configDir Directory of the config file; `{ file: }` values in platform blocks resolve against it.
     * @param authConfig The `auth:` blocks of every `extends:` layer merged per host (root wins); null → [rawConfig].
     * @param configLayers Each `extends:` layer as (source, raw JSON), deepest ancestor first, the config file last.
     */
    class Pass(
        message: String,
        val config: Config? = null,
        val rawConfig: JsonObject? = null,
        val configDir: String? = null,
        val authConfig: JsonElement? = null,
        val configLayers: List<Pair<String, JsonElement>> = emptyList(),
    ) : DoctorCheckResult(message)

    /** Check failed but non-fatal. [message] states the problem; [hint], when known, says how to fix it. */
    class Fail(
        message: String,
        val hint: String? = null,
    ) : DoctorCheckResult(message)

    /** Check passed with a warning that does not fail `doctor`. [hint], when known, says how to fix it. */
    class Warn(
        message: String,
        val hint: String? = null,
    ) : DoctorCheckResult(message)

    /** Check failed fatally (e.g. config parse error). [message] states the problem; [hint], when known, says how to fix it. */
    class Fatal(
        message: String,
        val hint: String? = null,
    ) : DoctorCheckResult(message)
}

/**
 * Common doctor check logic shared between JVM and Native DoctorCommand implementations.
 */
object DoctorCommandCommon {
    /** `doctor --offline`: one Pass per platform configured under `platforms:`, without calling any API. */
    fun listConfiguredPlatforms(configResult: DoctorCheckResult): List<DoctorCheckResult> {
        if (configResult !is DoctorCheckResult.Pass) return emptyList()
        return configResult.config
            ?.platforms
            ?.keys
            .orEmpty()
            .map { DoctorCheckResult.Pass("$it: configured (not checked: --offline)") }
    }

    /**
     * Checks every `pomsky:` pattern of the config ([PomskyConfigCheck]): a compile error is a Fail, a lint or
     * complexity finding a Warn. Empty when the config check did not pass.
     */
    fun checkPatterns(configResult: DoctorCheckResult): List<DoctorCheckResult> {
        val rawConfig = (configResult as? DoctorCheckResult.Pass)?.rawConfig ?: return emptyList()
        return PomskyConfigCheck
            .check(rawConfig)
            .map { if (it.error) DoctorCheckResult.Fail(it.message, it.hint) else DoctorCheckResult.Warn(it.message, it.hint) }
            .ifEmpty { listOf(DoctorCheckResult.Pass("pomsky patterns compile without warnings")) }
    }

    /**
     * Checks the credentials of every platform configured under `platforms:` against its API.
     *
     * Each platform service is initialized with its config block and asked [PlatformServiceModule.checkAuth];
     * rejected credentials or an API/network failure is a Fail. Returns an empty list when no platform is
     * configured (the caller displays a SKIP message) or the config check did not pass.
     *
     * @param configResult The config check result (must be Pass to proceed).
     */
    suspend fun checkAuth(
        configResult: DoctorCheckResult,
        services: List<PlatformServiceModule>,
        contextFor: (moduleId: String, config: JsonElement) -> PluginContext,
    ): List<DoctorCheckResult> {
        if (configResult !is DoctorCheckResult.Pass) return emptyList()
        val platforms = configResult.config?.platforms
        if (platforms.isNullOrEmpty()) return emptyList()
        return platforms.map { (key, platformConfig) ->
            val service = services.firstOrNull { ModuleIdNormalizer.matches(it.configKey, key) || ModuleIdNormalizer.matches(it.id, key) }
            if (service == null) {
                DoctorCheckResult.Fail(
                    "$key: no platform service handles 'platforms.$key'",
                    hint = "Remove 'platforms.$key' or install the plugin that provides it.",
                )
            } else {
                checkPlatformAuth(key, service, contextFor(service.id, platformConfig))
            }
        }
    }

    private suspend fun checkPlatformAuth(
        key: String,
        service: PlatformServiceModule,
        context: PluginContext,
    ): DoctorCheckResult {
        if (!service.initialize(context)) {
            return DoctorCheckResult.Fail(
                "$key: platform service failed to initialize",
                hint = "Check the 'platforms.$key' block: its URL and token settings.",
            )
        }
        val failed = { e: Exception -> DoctorCheckResult.Fail("$key: credential check failed: ${e.message}") }
        return try {
            when (service.checkAuth()) {
                true -> DoctorCheckResult.Pass(
                    "$key: credentials accepted" +
                        service.rateLimitStatus()?.let { " (API rate limit: ${it.remaining}/${it.limit} remaining)" }.orEmpty(),
                )

                false -> DoctorCheckResult.Fail("$key: credentials rejected (HTTP 401/403)", hint = service.tokenLocationHint())

                null -> DoctorCheckResult.Pass("$key: configured (this platform cannot verify credentials)")
            }
        } catch (e: KarandashException) {
            failed(e)
        } catch (e: IOException) {
            e.rethrowCancellation()
            failed(e)
        } catch (e: IllegalStateException) {
            // a service whose credentials could not be resolved stays uninitialized and says so here
            e.rethrowCancellation()
            failed(e)
        } finally {
            service.shutdown()
        }
    }
}
