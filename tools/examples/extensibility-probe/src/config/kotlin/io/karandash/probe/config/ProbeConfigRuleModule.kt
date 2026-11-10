package io.karandash.probe.config

import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.PluginContext
import io.karandash.sdk.module.RuleModule
import io.karandash.sdk.typed.TypedModule
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement

/** Shape of the module-owned top-level `probe:` config block. */
@Serializable
data class ProbeConfig(
    @SerialName("max-title-length")
    val maxTitleLength: Int = DEFAULT_MAX_TITLE_LENGTH,
) {
    companion object {
        const val DEFAULT_MAX_TITLE_LENGTH = 72
    }
}

/**
 * Module-owned-config probe: owns a top-level `probe:` block (via [configKey]) decoded through [configSerializer].
 *
 * The SDK surface used here already exists, so this source set compiles today. Whether the block
 * actually reaches the module (claimed into `Config.extensions` and delivered as `pluginConfig`
 * instead of being stripped as an unknown key) is a runtime property of core/config - the compile
 * check alone cannot prove it. See README.md.
 */
class ProbeConfigRuleModule :
    RuleModule,
    TypedModule<ProbeConfig> {
    override val id: String = ID
    override val name: String = "Extensibility probe — config owner"
    override val version: String = "0.0.0"
    override val configKey: String = CONFIG_KEY
    override val configSerializer: KSerializer<ProbeConfig> = ProbeConfig.serializer()

    override suspend fun initialize(context: PluginContext): Boolean =
        try {
            KarandashJson.Default.decodeFromJsonElement(configSerializer, context.pluginConfig)
            true
        } catch (e: SerializationException) {
            context.warn("probe: invalid `$CONFIG_KEY:` block: ${e.message}")
            false
        }

    override suspend fun contributedRules(): Set<String> = setOf(RULE_ID)

    override suspend fun validate(
        ruleId: String,
        mr: MergeRequest,
        configElement: JsonElement,
    ): List<Finding> {
        val config = KarandashJson.Default.decodeFromJsonElement(configSerializer, configElement)
        return if (mr.title.length > config.maxTitleLength) {
            listOf(Finding(severity = Severity.WARNING, message = "probe: title too long", rule = RULE_ID))
        } else {
            emptyList()
        }
    }

    companion object {
        const val ID = "probe-config"
        const val CONFIG_KEY = "probe"
        const val RULE_ID = "probe-title-length"
    }
}
