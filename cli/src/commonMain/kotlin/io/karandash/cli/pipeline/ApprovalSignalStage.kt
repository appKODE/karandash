package io.karandash.cli.pipeline

import io.karandash.api.support.rethrowCancellation
import io.karandash.models.Approval
import io.karandash.models.ApprovalMethod
import io.karandash.models.ApprovalSignal
import io.karandash.models.MergeRequest
import io.karandash.sdk.Logger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Adds the comment and reaction approvals the approval rule's `methods` asks for to the MR's approvals before
 * validation: [fetch] (`PlatformServiceModule.fetchApprovalSignals`) is called only when `methods` lists
 * `COMMENT_KEYWORD` or `EMOJI_REACTION`, and only signals of a listed method matching `commentKeywords` / `approvalEmojis` count.
 * A user who already approved through the platform is not counted twice.
 */
internal class ApprovalSignalStage(
    private val logger: Logger,
) {
    suspend fun apply(
        mr: MergeRequest,
        ruleConfig: JsonElement?,
        fetch: (suspend () -> List<ApprovalSignal>)?,
    ): MergeRequest {
        val config = ruleConfig as? JsonObject ?: return mr
        val methods = strings(config["methods"]).orEmpty()
        if (fetch == null || methods.none { it in SIGNAL_METHODS }) return mr
        @Suppress("TooGenericExceptionCaught")
        val signals = try {
            fetch()
        } catch (e: Exception) {
            // boundary: platform service calls are plugin code
            e.rethrowCancellation()
            logger.warn("Approval signals could not be fetched, comment and reaction approvals are ignored: ${e.message}")
            return mr
        }
        val keywords = strings(config["commentKeywords"]) ?: ApprovalSignal.DEFAULT_KEYWORDS
        val emojis = strings(config["approvalEmojis"]) ?: ApprovalSignal.DEFAULT_EMOJIS
        val counted = mr.approvals.map { it.user.username }.toMutableSet()
        val added = mutableListOf<Approval>()
        for (signal in signals.filter { it.method.name in methods }) {
            val approval = signal.toApproval(keywords, emojis) ?: continue
            if (counted.add(approval.user.username)) added += approval
        }
        return mr.copy(approvals = mr.approvals + added)
    }

    private fun strings(element: JsonElement?): List<String>? =
        (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private companion object {
        val SIGNAL_METHODS = setOf(ApprovalMethod.COMMENT_KEYWORD.name, ApprovalMethod.EMOJI_REACTION.name)
    }
}
