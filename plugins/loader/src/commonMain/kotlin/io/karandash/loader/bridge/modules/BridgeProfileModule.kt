package io.karandash.loader.bridge.modules

import io.karandash.api.ModuleType
import io.karandash.api.module.ProfileModule
import io.karandash.api.support.suspendCatching
import io.karandash.loader.bridge.BridgePart
import io.karandash.loader.bridge.BridgeTransport
import io.karandash.loader.bridge.guarded
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Shared [ProfileModule] implementation using [BridgeTransport].
 */
internal class BridgeProfileModule(
    transport: BridgeTransport,
) : BridgePart(transport),
    ProfileModule {
    override val type: ModuleType get() = ModuleType.PROFILE
    private val json = KarandashJson.Default

    override val profileId: String get() = transport.getProperty(PROP_PROFILE_ID).ifEmpty { id }
    override val summary: String get() = transport.getProperty(PROP_SUMMARY)

    override val isDefaultZeroConfigProfile: Boolean
        get() = transport.getProperty(PROP_IS_DEFAULT_ZERO_CONFIG).toBoolean()

    override val defaultSeverity: Severity
        get() {
            val raw = transport.getProperty(PROP_DEFAULT_SEVERITY)
            return suspendCatching { Severity.valueOf(raw.uppercase()) }.getOrDefault(Severity.WARNING)
        }

    override suspend fun tags(): Set<String> {
        if (!transport.hasCapability(METHOD_TAGS)) return emptySet()
        val result = transport.call(METHOD_TAGS) ?: return emptySet()
        return suspendCatching { json.decodeFromString<List<String>>(result).toSet() }.getOrDefault(emptySet())
    }

    override suspend fun ruleConfigs(): Map<String, JsonElement> {
        if (!transport.hasCapability(METHOD_RULE_CONFIGS)) return emptyMap()
        val result = transport.call(METHOD_RULE_CONFIGS) ?: return emptyMap()
        return transport.guarded(METHOD_RULE_CONFIGS, emptyMap()) {
            val obj = json.parseToJsonElement(result)
            if (obj is JsonObject) obj.toMap() else emptyMap()
        }
    }

    override suspend fun reporterConfigs(): Map<String, JsonElement> {
        if (!transport.hasCapability(METHOD_REPORTER_CONFIGS)) return emptyMap()
        val result = transport.call(METHOD_REPORTER_CONFIGS) ?: return emptyMap()
        return transport.guarded(METHOD_REPORTER_CONFIGS, emptyMap()) {
            val obj = json.parseToJsonElement(result)
            if (obj is JsonObject) obj.toMap() else emptyMap()
        }
    }

    private companion object {
        const val PROP_PROFILE_ID = "profileId"
        const val PROP_SUMMARY = "summary"
        const val PROP_IS_DEFAULT_ZERO_CONFIG = "isDefaultZeroConfigProfile"
        const val PROP_DEFAULT_SEVERITY = "defaultSeverity"
        const val METHOD_TAGS = "tags"
        const val METHOD_RULE_CONFIGS = "ruleConfigs"
        const val METHOD_REPORTER_CONFIGS = "reporterConfigs"
    }
}
