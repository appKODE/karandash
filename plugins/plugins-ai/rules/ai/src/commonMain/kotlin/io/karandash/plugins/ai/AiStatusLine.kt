package io.karandash.plugins.ai

import io.karandash.api.finding.SkipCategory
import io.karandash.models.finding.Finding
import io.karandash.models.finding.LocalizedText
import io.karandash.models.finding.Translations
import io.karandash.models.finding.withText
import io.karandash.plugins.ai.core.incremental.IncrementalContext
import io.karandash.sdk.Logger
import io.karandash.utils.finding.FindingFactory
import kotlinx.serialization.Serializable

/**
 * Whether the provider answered this run.
 *
 * @property calls AI rules that reached the provider, answered or failed.
 * @property failed Of those, the calls that failed (timeout, HTTP error, connection).
 */
@Serializable
internal data class AiHealth(
    val calls: Int,
    val failed: Int,
) {
    /** True when every call failed: the run had no AI review at all. */
    val unavailable: Boolean get() = calls > 0 && failed == calls

    /** `12/12 calls failed`. */
    val failedText: String get() = "$failed/$calls calls failed"
}

internal fun AiRuleState.health(): AiHealth {
    // a rule whose dispatch completed counts as answered even when some of its chunks failed
    val failed = ruleSkipReasons.values
        .filter { it.category == SkipCategory.AI_PROVIDER_ERROR && it.ruleId !in ruleModels }
        .map { it.ruleId }
    return AiHealth(calls = (ruleModels.keys + failed).size, failed = failed.size)
}

/**
 * The status line under the MR note's heading; null when AI is not [configured]. A provider outage, a policy that
 * switched AI off and a cache-only run each read differently, so "no AI findings" is never ambiguous.
 */
internal fun AiRuleState.aiStatus(configured: Boolean): String? = aiStatusLine(configured)?.let { (text, args) -> text.of(null, args) }

/** [aiStatus] as its [AiStatusText] and values, so a report renders it in its own language. */
internal fun AiRuleState.aiStatusLine(configured: Boolean): Pair<AiStatusText, List<String>>? {
    if (!configured) return null
    val health = health()
    val policySkip = engineSkips.values.firstOrNull { it.category == SkipCategory.DISABLED_BY_POLICY }
    val firstReason = (ruleSkipReasons.values + engineSkips.values).firstOrNull()?.reason
    val failed = listOf("${health.failed}", "${health.calls}")
    return when {
        health.unavailable -> {
            AiStatusText.UNAVAILABLE to failed
        }

        health.failed > 0 -> {
            AiStatusText.PARTLY_UNAVAILABLE to failed
        }

        health.calls > 0 -> {
            AiStatusText.OK to
                listOf("${health.calls}", tokenText(ruleTokenCounts.values.sum() + auxiliaryTokens.values.sum()))
        }

        policySkip != null && policySkip.reason.startsWith(POLICY_PREFIX) -> {
            AiStatusText.OFF_BY_POLICY to listOf(policySkip.reason.removePrefix(POLICY_PREFIX))
        }

        policySkip != null -> {
            AiStatusText.OFF_BY to listOf(policySkip.reason.removePrefix(DISABLED_PREFIX))
        }

        cachedFindings != null -> {
            AiStatusText.FROM_CACHE to listOf("$cachedFindings")
        }

        firstReason != null -> {
            AiStatusText.NOT_RUN_BECAUSE to listOf(firstReason)
        }

        else -> {
            AiStatusText.NOT_RUN to emptyList()
        }
    }
}

/** The AI status line texts in English; other languages (`report.language`) from a pack. `%1`, `%2` are values. */
internal enum class AiStatusText(
    val en: String,
) {
    UNAVAILABLE("AI: unavailable — %1/%2 calls failed"),
    PARTLY_UNAVAILABLE("AI: partly unavailable — %1/%2 calls failed"),
    OK("AI: ok — %1 rules, %2 tokens"),
    OFF_BY_POLICY("AI: off by policy %1"),
    OFF_BY("AI: off by %1"),
    FROM_CACHE("AI: %1 findings from cache"),
    NOT_RUN_BECAUSE("AI: not run — %1"),
    NOT_RUN("AI: not run"),
    ;

    /** This text in [language] (English when unset or without a pack), `%1`.. filled with [values]. */
    fun of(
        language: String?,
        values: List<String>,
    ): String = values.foldIndexed(Translations.text(en, language)) { i, acc, v -> acc.replace("%${i + 1}", v) }
}

/** Rule id of the INFO finding that says the provider failed this run. */
internal const val AI_HEALTH_RULE = "ai:health"

/**
 * When provider calls failed, one INFO log line and one INFO finding say so; null when every call answered.
 * A failure is not the MR's fault, so it never blocks the merge.
 */
internal fun healthFinding(
    health: AiHealth,
    logger: Logger,
): Finding? {
    if (health.failed == 0) return null
    val text = "AI ${if (health.unavailable) "unavailable" else "partly unavailable"} (${health.failedText})"
    logger.info(text)
    val message = (if (health.unavailable) AiHealthText.UNAVAILABLE else AiHealthText.PARTLY_UNAVAILABLE).of(health.failed, health.calls)
    return FindingFactory.info(rule = AI_HEALTH_RULE, message = message.en).withText(message = message)
}

/** The [healthFinding] message in English; other languages from a pack (`{0}` failed of `{1}` calls). */
internal enum class AiHealthText(
    override val en: String,
) : LocalizedText {
    UNAVAILABLE("AI unavailable ({0}/{1} calls failed): the review is incomplete, see the job log"),
    PARTLY_UNAVAILABLE("AI partly unavailable ({0}/{1} calls failed): the review is incomplete, see the job log"),
}

/**
 * Why this run's findings must not replace the incremental cache; null saves. A failed provider call or a run that
 * consumed no tokens on changed files left rules unreviewed, so the previous cache is kept for the retry.
 */
internal fun cacheSaveSkipReason(
    context: IncrementalContext,
    health: AiHealth?,
    tokensConsumed: Int?,
): String? =
    when {
        health != null && health.failed > 0 -> "AI unavailable (${health.failedText})"
        context.changedFiles.isNotEmpty() && tokensConsumed == 0 -> "no tokens consumed"
        else -> null
    }

/** `950`, `23.1k`. */
internal fun tokenText(tokens: Int): String {
    if (tokens < THOUSAND) return tokens.toString()
    val tenths = (tokens + TENTH / 2) / TENTH
    return "${tenths / TEN}.${tenths % TEN}k"
}

private const val DISABLED_PREFIX = "disabled by "
private const val POLICY_PREFIX = "disabled by policy "
private const val THOUSAND = 1000
private const val TENTH = 100
private const val TEN = 10
