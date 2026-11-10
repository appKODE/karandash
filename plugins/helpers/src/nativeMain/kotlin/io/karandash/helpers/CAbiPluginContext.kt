package io.karandash.helpers

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAwarePluginContext
import io.karandash.api.ai.AiService
import io.karandash.api.cache.CacheProvider
import io.karandash.api.exception.ContentResolveException
import io.karandash.interpolation.KarandashEnvSecurity
import io.karandash.models.support.KarandashJson
import io.karandash.platform.PlatformEnvironment
import io.karandash.sdk.Logger
import io.karandash.sdk.content.ContentSource
import io.karandash.sdk.context.BridgeHostSettings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The [PluginContext] a K/N guest sees, built from the host's context JSON (`BridgeContextSerializer`) by
 * [CAbiBridge.contextFromJson]: every field the host sends, the host-filtered env and the host-resolved named patterns.
 * With the ABI v2 host channel ([CAbiHost]) set, logging, the cache, AI and content resolution go to the host.
 */
internal class CAbiPluginContext(
    private val fields: JsonObject,
) : AiAwarePluginContext {
    override val pluginConfig: JsonElement = fields["pluginConfig"] as? JsonObject ?: JsonObject(emptyMap())
    override val hostVersion: String = string("hostVersion").orEmpty()
    override val workingDirectory: String = string("workingDirectory").orEmpty()
    override val configDir: String = string("configDir") ?: workingDirectory
    override val allowLocalhost: Boolean = (fields["allowLocalhost"] as? JsonPrimitive)?.booleanOrNull ?: false

    /** Host log over the channel; without it the guest shares the host's stderr and debug and info stay quiet. */
    override val logger: Logger get() = if (CAbiHost.connected) HOST_LOGGER else StderrLogger

    override val cacheProvider: CacheProvider get() = if (CAbiHost.connected) HostCacheProvider else CacheProvider.NOOP

    override val ai: AiService? get() = if (CAbiHost.connected) HostAiService() else null

    private val hostEnv = fields["env"] as? JsonObject ?: JsonObject(emptyMap())

    private fun string(key: String) = (fields[key] as? JsonPrimitive)?.contentOrNull

    /** Only from the host-supplied env (already filtered by the host), never this process's own environment. */
    override fun env(name: String): String? = KarandashEnvSecurity.withoutSecrets { (hostEnv[it] as? JsonPrimitive)?.contentOrNull }(name)

    override fun resolvePattern(pattern: String): Regex = BridgeHostSettings.pattern(fields, pattern) ?: super.resolvePattern(pattern)

    @ExperimentalKarandashApi
    override suspend fun resolveContent(source: ContentSource): String =
        if (CAbiHost.connected) viaHost(source) else inlineContentOnly(source)

    private object StderrLogger : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) = PlatformEnvironment.stderrPrintln("[native-guest:warn] $message")

        override fun error(message: String) = PlatformEnvironment.stderrPrintln("[native-guest:error] $message")
    }

    companion object {
        /** The context of a guest whose host sent no (or unreadable) context JSON. */
        val EMPTY = CAbiPluginContext(JsonObject(emptyMap()))

        private val HOST_LOGGER: Logger = HostLogger(StderrLogger)

        private fun viaHost(source: ContentSource): String {
            val reply =
                CAbiHost.request(
                    "resolveContent",
                    buildJsonObject { put("source", KarandashJson.Default.encodeToJsonElement(ContentSource.serializer(), source)) },
                )
            return reply.str("content") ?: throw unresolved(source, reply.str("err") ?: "no content")
        }

        /** A v1 host: no `auth:` block or fetcher on this side, only inline content resolves. */
        private fun inlineContentOnly(source: ContentSource): String =
            (source as? ContentSource.Inline)?.value
                ?: throw unresolved(source, "resolveContent is not supported through the C-ABI bridge; only inline content is available.")

        private fun unresolved(
            source: ContentSource,
            message: String,
        ) = ContentResolveException(
            sourceType = source::class.simpleName ?: "ContentSource",
            reference = source.toString(),
            message = message,
        )
    }
}
