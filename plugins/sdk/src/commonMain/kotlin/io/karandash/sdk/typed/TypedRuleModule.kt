package io.karandash.sdk.typed

import io.karandash.api.errors.KarandashError
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.sdk.config.deserializeConfigReporting
import io.karandash.sdk.module.RuleModule
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * A [RuleModule] that receives its config as a typed class [C] instead of a raw map.
 *
 * Modules implementing this interface get type safety for free: the bridge
 * deserializes config automatically, so [validateTyped] always receives
 * a fully-typed config instance.
 *
 * New modules supply [configSerializer] directly.
 *
 * @param C The typed config class for this module.
 */
public interface TypedRuleModule<C : Any> :
    RuleModule,
    TypedModule<C> {
    /**
     * Validate the merge request against this rule with a fully-typed config.
     *
     * @param ruleId The rule ID being validated.
     * @param mr The merge request data.
     * @param config The typed config instance.
     * @return List of findings (empty = pass).
     */
    public suspend fun validateTyped(
        ruleId: String,
        mr: MergeRequest,
        config: C
    ): List<Finding>

    /**
     * Deserializes [JsonElement] via [configSerializer], then delegates to [validateTyped].
     *
     * A config that fails to decode still runs on the defaults (as before), plus one
     * [Severity.INFO] finding naming the rule and the decode error so the bad config is visible.
     */
    override suspend fun validate(
        ruleId: String,
        mr: MergeRequest,
        configElement: JsonElement,
    ): List<Finding> {
        val (config, decodeError) = deserializeConfigReporting(configSerializer, configElement)
        val findings = validateTyped(ruleId, mr, config)
        if (decodeError == null || configElement is JsonNull) return findings
        return findings + Finding(
            severity = Severity.INFO,
            message = "Invalid config for rule '$ruleId', using defaults: $decodeError",
            rule = ruleId,
            errorCode = KarandashError.INVALID_CONFIG_VALUE_TYPE.code,
        )
    }
}
