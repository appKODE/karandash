package io.karandash.config.pipeline

import io.karandash.config.RuleExecutionMeta
import io.karandash.logger.Logger
import io.karandash.models.finding.Severity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Rule-config key checks run while `rules:` is parsed:
 * - a boolean `block*` key (`blockDraft`, `blockOnConflicts`, `blockOnFailing`, `blockOnDraft`) keeps working with
 *   its rule's old meaning, and one info note per parse points at the `fail | warn | off` form.
 */
internal object RuleKeyAliases {
    private val BLOCK_KEY = Regex("^block[A-Z].*")

    /** Dotted paths of the boolean `block*` keys anywhere in [rule], prefixed with [ruleId]. */
    fun booleanBlockKeys(
        ruleId: String,
        rule: JsonElement,
    ): List<String> =
        when (rule) {
            is JsonObject -> rule.flatMap { (key, value) ->
                if (BLOCK_KEY.matches(key) && value.isBooleanLiteral()) {
                    listOf("$ruleId.$key")
                } else {
                    booleanBlockKeys("$ruleId.$key", value)
                }
            }

            is JsonArray -> rule.flatMapIndexed { i, item -> booleanBlockKeys("$ruleId[$i]", item) }

            else -> emptyList()
        }

    private fun JsonElement.isBooleanLiteral(): Boolean = this is JsonPrimitive && !isString && booleanOrNull != null

    /** One info line naming every boolean `block*` key, or nothing when there are none. */
    fun noteBooleanBlockKeys(
        paths: List<String>,
        logger: Logger,
    ) {
        if (paths.isEmpty()) return
        logger.info(
            "rules: ${paths.joinToString(", ")} use a boolean; it still works with its old meaning, " +
                "but write fail | warn | off instead",
        )
    }

    /** Warns when a rule's `severity:` names no [Severity]; the engine then keeps each finding's own severity. */
    fun warnUnknownSeverity(
        ruleId: String,
        meta: RuleExecutionMeta,
        logger: Logger,
    ) {
        val value = meta.severity ?: return
        if (Severity.entries.none { it.name.equals(value, ignoreCase = true) }) {
            logger.warn(
                "rules.$ruleId.severity '$value' is not one of error, warning, info; it is ignored and findings keep their own severity"
            )
        }
    }

    /** `rules.x: true` enables a rule with its defaults and `x: false` disables it; any other scalar (`x: "on"`) is ignored, so say so. */
    fun warnScalarRule(
        ruleId: String,
        value: JsonElement,
        logger: Logger,
    ) {
        if (value is JsonPrimitive && value !is JsonNull && value.booleanOrNull == null) {
            logger.warn("Config key 'rules.$ruleId' value '${value.content}' is neither true/false nor a rule config; it is ignored")
        }
    }
}
