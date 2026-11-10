package io.karandash.cli.pipeline

import io.karandash.api.finding.ProvenanceStatus
import io.karandash.api.finding.ReviewProvenance
import io.karandash.api.finding.ValidationReport
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.support.rethrowCancellation
import io.karandash.cli.commands.validate.ResolvedFormatOutput
import io.karandash.helpers.output.AiRunStatus
import io.karandash.helpers.output.NotificationState
import io.karandash.helpers.output.NotifyWhen
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.Logger
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Applies the outputs' `when` key: drops the pairs this run should not deliver, and hands outputs that post to the
 * MR this run's [NotificationState] (they compare it with their own summary comment). "Changed" compares with the
 * state the platform summary comment carries; without a platform (a local run) every run counts as changed.
 *
 * @lifecycle per-run -- built for one report generation.
 */
internal class NotificationGate(
    private val logger: Logger,
) {
    private var warnedNoMarker = false

    /** [pairs] without those `when` holds back; [platform] posts to [projectId]/[mrId], or is null for a local run. */
    suspend fun apply(
        pairs: List<ResolvedFormatOutput>,
        report: ValidationReport,
        platform: PlatformServiceModule?,
        projectId: String?,
        mrId: String?,
    ): List<ResolvedFormatOutput> {
        val state = NotificationState.of(report.passed, report.findings.map(FindingSuppression::key))
        val ai = report.provenance
        val target = if (platform != null && projectId != null && mrId != null) Triple(platform, projectId, mrId) else null
        val whens = pairs.associateWith { if (it.outputModule.requiresPlatformContext) null else notifyWhen(it) }
        val wantsChanged = NotifyWhen.CHANGED in whens.values
        // one read of the summary comment serves the `when: changed` outputs and the platform output (which skips its own)
        val previous =
            if (target == null || (!wantsChanged && null !in whens.values)) {
                null
            } else {
                previousState(target.first, target.second, target.third)
            }
        if (target != null && wantsChanged && previous == null) warnNoMarker()
        val changed = previous?.let { !state.sameOutcome(it) }
        return pairs.mapNotNull { pair ->
            val notifyWhen = whens[pair]
            when {
                notifyWhen == null -> {
                    if (target != null) pair.withState(state, previous, ai) else pair
                }

                notifyWhen.allows(state.passed, changed) -> {
                    if (target != null) pair.withTarget("${target.second}!${target.third}") else pair
                }

                else -> {
                    logger.info("${pair.outputModule.outputId}: not sent, when=${notifyWhen.name.lowercase()} and nothing calls for it")
                    null
                }
            }
        }
    }

    /** Once per run: without a state marker on the MR every `when: changed` run delivers. */
    private fun warnNoMarker() {
        if (warnedNoMarker) return
        warnedNoMarker = true
        logger.warn(
            "No summary state marker on the MR (is the platform output posting a summary?): when=changed outputs deliver every run",
        )
    }

    private suspend fun notifyWhen(pair: ResolvedFormatOutput): NotifyWhen {
        val element: JsonElement =
            (pair.outputConfig as? JsonObject)?.get(WHEN_KEY)
                ?: (pair.outputModule.defaultConfig() as? JsonObject)?.get(WHEN_KEY)
                ?: return NotifyWhen.ALWAYS
        return try {
            KarandashJson.Default.decodeFromJsonElement(NotifyWhen.serializer(), element)
        } catch (e: SerializationException) {
            logger.warn("${pair.outputModule.outputId}: unknown when '$element' (always, changed, failed, never), delivering: ${e.message}")
            NotifyWhen.ALWAYS
        } catch (e: IllegalArgumentException) {
            logger.warn("${pair.outputModule.outputId}: unknown when '$element' (always, changed, failed, never), delivering: ${e.message}")
            NotifyWhen.ALWAYS
        }
    }

    /** The state the MR's summary comment carries; null when there is none or it cannot be read (counts as changed). */
    @Suppress("TooGenericExceptionCaught") // boundary: an unreadable previous state only means "deliver"
    private suspend fun previousState(
        platform: PlatformServiceModule,
        projectId: String,
        mrId: String,
    ): NotificationState? =
        try {
            NotificationState.parse(platform.findCommentBody(projectId, mrId, NotificationState.PREFIX))
        } catch (e: Exception) {
            // boundary: platform API call; outputs with when=changed then deliver
            e.rethrowCancellation()
            logger.warn("Could not read the last summary state, delivering when=changed outputs: ${e.message}")
            warnedNoMarker = true
            null
        }

    /** An output that keys its message by MR (a `notificationTarget` key in its config) gets this run's MR. */
    private suspend fun ResolvedFormatOutput.withTarget(target: String): ResolvedFormatOutput {
        val config = outputConfig as? JsonObject ?: return this
        if ((outputModule.defaultConfig() as? JsonObject)?.containsKey(TARGET_KEY) != true) return this
        return ResolvedFormatOutput(formatModule, outputModule, formatConfig, JsonObject(config + (TARGET_KEY to JsonPrimitive(target))))
    }

    private fun ResolvedFormatOutput.withState(
        state: NotificationState,
        previous: NotificationState?,
        provenance: ReviewProvenance?,
    ): ResolvedFormatOutput {
        val config = outputConfig as? JsonObject ?: return this
        val language = (config[LANGUAGE_KEY] as? JsonPrimitive)?.contentOrNull
        val ai = provenance?.let { p ->
            p.aiStatusIn(language)?.let { AiRunStatus(it, passed = p.analyzers.none { a -> a.status == ProvenanceStatus.FAILED }) }
        }
        val json = KarandashJson.Default
        val extra = listOfNotNull(
            STATE_KEY to json.encodeToJsonElement(NotificationState.serializer(), state),
            previous?.let { PREVIOUS_STATE_KEY to json.encodeToJsonElement(NotificationState.serializer(), it) },
            ai?.let { AI_STATUS_KEY to json.encodeToJsonElement(AiRunStatus.serializer(), it) },
        )
        return ResolvedFormatOutput(formatModule, outputModule, formatConfig, JsonObject(config + extra))
    }

    private companion object {
        const val WHEN_KEY = "when"
        const val LANGUAGE_KEY = "language"
        const val STATE_KEY = "notificationState"
        const val PREVIOUS_STATE_KEY = "previousNotificationState"
        const val TARGET_KEY = "notificationTarget"
        const val AI_STATUS_KEY = "aiStatus"
    }
}
