@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.engine

import io.karandash.api.ModuleRegistry
import io.karandash.api.context.ConditionPredicate
import io.karandash.api.finding.SkipCategory
import io.karandash.api.finding.SkippedRuleInfo
import io.karandash.api.module.PlatformServiceModule
import io.karandash.logger.Logger
import kotlinx.serialization.json.JsonElement

/**
 * Splits [ruleIds] into rules that can run on [platform] and rules to skip because they require a
 * merge-request field the platform never fills ([PlatformServiceModule.unavailableMergeRequestFields]).
 * Each skip logs one warning, so a rule never passes silently on an empty field.
 */
internal fun filterByRequiredMergeRequestFields(
    ruleIds: List<String>,
    registry: ModuleRegistry,
    platform: PlatformServiceModule?,
    logger: Logger,
    configsOf: (String) -> List<JsonElement> = { emptyList() },
): Pair<List<String>, List<SkippedRuleInfo>> {
    val unavailable = platform?.unavailableMergeRequestFields().orEmpty()
    if (platform == null || unavailable.isEmpty()) return ruleIds to emptyList()
    val skipped = mutableListOf<SkippedRuleInfo>()
    val active = ruleIds.filter { ruleId ->
        val handler = registry.findRuleHandler(ruleId)
        val configs = configsOf(ruleId)
        val required = when {
            handler == null -> emptySet()
            configs.isEmpty() -> handler.requiredMergeRequestFields(ruleId)
            else -> configs.flatMapTo(mutableSetOf()) { handler.requiredMergeRequestFields(ruleId, it) }
        }
        val missing = required.intersect(unavailable)
        if (missing.isNotEmpty()) {
            val fields = missing.joinToString(", ") { it.displayName }
            val reason = "platform '${platform.id}' doesn't provide $fields"
            logger.warn("Rule '$ruleId' skipped: $reason")
            skipped.add(SkippedRuleInfo(ruleId, reason, SkipCategory.MISSING_MERGE_REQUEST_FIELD))
        }
        missing.isEmpty()
    }
    return active to skipped
}

private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

/**
 * Warns once per predicate that reads a merge-request field [platform] never fills
 * ([ConditionPredicate.requiredField]) and is used in a rule's `when` ([whenByRule]: rule id to expression):
 * such a predicate sees an empty value, so it is always false (and its negation always true).
 */
internal fun warnPredicatesOnMissingFields(
    whenByRule: Map<String, String>,
    predicates: List<ConditionPredicate<*>>,
    platform: PlatformServiceModule?,
    logger: Logger,
) {
    val unavailable = platform?.unavailableMergeRequestFields().orEmpty()
    if (platform == null || unavailable.isEmpty() || whenByRule.isEmpty()) return
    val namesByRule = whenByRule.mapValues { (_, expression) -> IDENTIFIER.findAll(expression).mapTo(mutableSetOf()) { it.value } }
    for (predicate in predicates) {
        val field = predicate.requiredField?.takeIf { it in unavailable } ?: continue
        val rules = namesByRule.filterValues { predicate.name in it }.keys
        if (rules.isEmpty()) continue
        val reason = "platform '${platform.id}' doesn't provide ${field.displayName}"
        logger.warn("Predicate '${predicate.name}' in 'when' of rule ${rules.joinToString { "'$it'" }} sees no value: $reason")
    }
}
