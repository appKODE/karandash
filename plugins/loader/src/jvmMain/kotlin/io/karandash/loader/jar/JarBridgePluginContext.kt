package io.karandash.loader.jar

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAwarePluginContext
import io.karandash.api.ai.AiService
import io.karandash.api.cache.CacheProvider
import io.karandash.loader.bridge.BridgeContextSerializer
import io.karandash.loader.bridge.GuestContentPolicy
import io.karandash.models.support.KarandashJson
import io.karandash.patterns.CorePatternRegistry
import io.karandash.patterns.PatternCompilationResult
import io.karandash.patterns.PatternResolver
import io.karandash.patterns.PomskyCompilerFactory
import io.karandash.sdk.content.ContentSource
import io.karandash.sdk.context.BridgeHostSettings
import io.karandash.sdk.regex.SafeRegex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * [PluginContext] for the JAR bridge process, rebuilt from the host's serialized context
 * (`BridgeContextSerializer`, sent as [io.karandash.subprocess.JarBridgeProtocol.PARAM_CONTEXT]).
 *
 * In a long-lived session [host] is the module's host-service channel: logs go to the host's logger, the cache is
 * the host's, [ai] completes through the host's `ai.complete`, patterns the host did not send are resolved by the host
 * and content by the host's resolver (with its repo fetcher). Each falls back to the per-request behaviour when the
 * host answers an error.
 *
 * Without a channel (per-request JVM) caching is the [PluginContext] default (no-op), [ai] is null, [resolvePattern]
 * serves the named patterns the host resolved for the guest's config and compiles every other pattern against the
 * core registry, and [resolveContent] follows [GuestContentPolicy] (no repo resolver: the host's `auth:` block and
 * fetcher do not cross the bridge).
 */
internal class JarBridgePluginContext(
    private val fields: JsonObject = JsonObject(emptyMap()),
    private val host: JarHostCall? = null,
) : PluginContext,
    AiAwarePluginContext {
    override val pluginConfig: JsonElement = fields["pluginConfig"] ?: JsonObject(emptyMap())
    override val hostVersion: String = string("hostVersion") ?: io.karandash.Version.VERSION
    override val workingDirectory: String = string("workingDirectory") ?: System.getProperty("user.dir") ?: "."
    override val configDir: String = string("configDir") ?: workingDirectory
    override val allowLocalhost: Boolean = (fields["allowLocalhost"] as? JsonPrimitive)?.booleanOrNull ?: false

    private val envVars = (fields["env"] as? JsonObject).orEmpty()
    private val hostPatterns = (fields[BridgeContextSerializer.PATTERNS] as? JsonObject).orEmpty()
    private val localPatterns by lazy { PatternResolver(CorePatternRegistry.create(), PomskyCompilerFactory.create()) }
    private val verbose = System.getProperty("karandash.bridge.verbose") == "true"

    private fun string(key: String) = (fields[key] as? JsonPrimitive)?.contentOrNull

    override val cacheProvider: CacheProvider = host?.let(::JarHostCacheProvider) ?: CacheProvider.NOOP

    override val ai: AiService? = host?.let { JarHostAiService(it) }

    /** stderr is forwarded by the host to its logger at debug; debug lines only when the host runs verbose. */
    override fun log(message: String) {
        if (host != null) {
            host.notify("log", logLine("info", message))
        } else if (verbose) {
            System.err.println("[jar-bridge] $message")
        }
    }

    override fun warn(message: String) {
        if (host != null) host.notify("log", logLine("warn", message)) else System.err.println("[jar-bridge:warn] $message")
    }

    private fun logLine(
        level: String,
        message: String,
    ) = buildJsonObject {
        put("level", level)
        put("message", message)
    }

    /** The host env the guest may see (`GuestEnv`: everything but the denylist and credential-like names). */
    override fun env(name: String): String? = (envVars[name] as? JsonPrimitive)?.contentOrNull

    override fun resolvePattern(pattern: String): Regex {
        if (pattern in hostPatterns) {
            return BridgeHostSettings.pattern(fields, pattern)
                ?: super<PluginContext>
                    .resolvePattern(
                        pattern
                    ).also { warn("Host pattern '$pattern' failed to compile here; matching it literally") }
        }
        hostPattern(pattern)?.let { return it }
        return when (val result = localPatterns.resolve(pattern)) {
            is PatternCompilationResult.Success -> {
                result.regex
            }

            is PatternCompilationResult.Error -> {
                warn("Pattern compilation failed for '$pattern': ${result.message}; matching it literally")
                super<PluginContext>.resolvePattern(pattern)
            }
        }
    }

    /** The host's resolution of [pattern] (`resolvePattern` callback), compiled here; null without a channel or answer. */
    private fun hostPattern(pattern: String): Regex? {
        val reply = host?.callBlocking("resolvePattern", buildJsonObject { put("pattern", pattern) }) ?: return null
        if (reply.text("err") != null) return null
        val entry = buildJsonObject {
            reply["pattern"]?.let { put(BridgeHostSettings.PATTERN_SOURCE, it) }
            reply["options"]?.let { put(BridgeHostSettings.PATTERN_OPTIONS, it) }
        }
        return BridgeHostSettings.pattern(JsonObject(mapOf(BridgeHostSettings.PATTERNS to JsonObject(mapOf(pattern to entry)))), pattern)
    }

    private val contentPolicy by lazy { GuestContentPolicy(configDir) }

    @ExperimentalKarandashApi
    override suspend fun resolveContent(source: ContentSource): String {
        val reply = host?.call(
            "resolveContent",
            buildJsonObject {
                put("source", KarandashJson.Default.encodeToJsonElement(ContentSource.serializer(), source))
            }
        )
        return reply?.takeIf { it.text("err") == null }?.text("content") ?: contentPolicy.resolve(source)
    }

    companion object {
        /**
         * Rebuilds the host context and applies its display settings ([BridgeHostSettings]) to this process;
         * without one (an older host) the defaults apply.
         */
        fun fromJson(
            json: String?,
            host: JarHostCall? = null,
        ): JarBridgePluginContext {
            val fields = json?.let { KarandashJson.Wire.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
            BridgeHostSettings.apply(fields)
            return JarBridgePluginContext(fields, host)
        }
    }
}
