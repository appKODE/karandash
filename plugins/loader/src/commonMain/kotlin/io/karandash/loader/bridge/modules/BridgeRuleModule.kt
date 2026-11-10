package io.karandash.loader.bridge.modules

import io.karandash.api.ModuleType
import io.karandash.api.module.RuleModule
import io.karandash.api.support.suspendCatching
import io.karandash.loader.bridge.BridgeConstants
import io.karandash.loader.bridge.BridgePart
import io.karandash.loader.bridge.BridgeTransport
import io.karandash.loader.bridge.guarded
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement

/**
 * Shared [RuleModule] implementation using [BridgeTransport].
 */
internal class BridgeRuleModule(
    transport: BridgeTransport
) : BridgePart(transport),
    RuleModule {
    override val type: ModuleType get() = ModuleType.RULE
    private val json = KarandashJson.Default

    override suspend fun contributedRules(): Set<String> {
        if (!transport.hasCapability(METHOD_CONTRIBUTED_RULES)) return emptySet()
        val result = transport.call(METHOD_CONTRIBUTED_RULES) ?: return emptySet()
        return suspendCatching { json.decodeFromString<List<String>>(result).toSet() }.getOrDefault(emptySet())
    }

    override suspend fun validate(
        ruleId: String,
        mr: MergeRequest,
        configElement: JsonElement
    ): List<Finding> {
        if (!transport.hasCapability(METHOD_VALIDATE)) return emptyList()
        return transport.guarded(METHOD_VALIDATE, emptyList()) {
            val mrJson = json.encodeToString(MergeRequest.serializer(), mr)
            val result = transport.call(METHOD_VALIDATE, ruleId, mrJson, configElement.toString())
                ?: return emptyList()
            json.decodeFromString(ListSerializer(Finding.serializer()), result)
        }
    }

    override suspend fun ruleExplanation(ruleId: String): String? {
        if (!transport.hasCapability("ruleExplanation")) return null
        return transport.call("ruleExplanation", ruleId)?.takeIf { it.isNotBlank() && it != BridgeConstants.NULL_SENTINEL }
    }

    private companion object {
        const val METHOD_CONTRIBUTED_RULES = "contributedRules"
        const val METHOD_VALIDATE = "validate"
    }
}
