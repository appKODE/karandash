package io.karandash.plugins.ai.core

import io.karandash.api.finding.InlineFindingRef
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.utils.finding.FindingFingerprint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/**
 * Stores dismissed finding signatures to filter repeat false positives on subsequent runs.
 *
 * Each entry is keyed by a fingerprint derived from (rule, file path, message hash). A reviewer's reply to a posted
 * finding is a typed verdict ([recordReply]): a rejection becomes an entry that also carries the finding's anchor
 * fingerprint (`fpv`), so a reworded finding on the same anchor is dropped too; an acceptance is kept apart in
 * [acceptedAnchors] and never filters anything.
 * The store is bounded by [maxEntries] with LRU eviction to prevent unbounded growth.
 *
 * Thread-safe via [MutableStateFlow] with immutable snapshots.
 *
 * @param maxEntries Maximum number of dismissed entries before LRU eviction.
 */
class FeedbackStore(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    @Serializable
    private data class FeedbackState(
        val entries: Map<String, DismissedEntry> = emptyMap(),
        val insertionOrder: List<String> = emptyList(),
        /** Index from semantic fingerprint to entry fingerprint for O(1) dual lookup. */
        val semanticIndex: Map<String, String> = emptyMap(),
        /** Anchor fingerprints of findings a reviewer accepted, oldest first, bounded by [maxEntries]. */
        val accepted: List<String> = emptyList(),
    )

    private val state = MutableStateFlow(FeedbackState())

    /**
     * Records a dismissal for the given (rule, filePath, messageHash) combination.
     *
     * If the fingerprint already exists the dismiss count and last-dismissed timestamp
     * are updated in-place. When [maxEntries] is exceeded the oldest entry is evicted.
     *
     * @param rule The rule ID that produced the finding.
     * @param filePath The file path from the finding location, or null for non-file findings.
     * @param messageHash Hash of the finding message (`finding.message.hashCode()`).
     * @param messagePrefix First [DismissedEntry.MESSAGE_PREFIX_MAX_LENGTH] characters of the
     *   finding message, used to generate negative prompt examples. Defaults to empty string
     *   when the caller only has the hash available.
     * @param semanticFingerprint Optional semantic fingerprint from [FindingFingerprint.generate].
     *   When provided, findings with different exact messages but the same semantic fingerprint
     *   will also be filtered. Defaults to empty string (no semantic matching for this entry).
     * @param anchorFingerprint The finding's `fpv`; when set, any finding with the same anchor fingerprint is filtered.
     * @param reason Why it was dismissed, e.g. a thread command (`wontfix`); empty when unknown.
     */
    fun dismiss(
        rule: String,
        filePath: String?,
        messageHash: Int,
        messagePrefix: String = "",
        semanticFingerprint: String = "",
        anchorFingerprint: String = "",
        reason: String = "",
    ) {
        val fp = fingerprint(rule, filePath, messageHash)
        val now = Clock.System.now().toEpochMilliseconds()
        state.update { s ->
            val existing = s.entries[fp]
            if (existing != null) {
                val newOrder = s.insertionOrder.filter { it != fp } + fp
                val updatedSemantic = semanticFingerprint.ifEmpty { existing.semanticFingerprint }
                s.copy(
                    entries = s.entries + (
                        fp to existing.copy(
                            dismissCount = existing.dismissCount + 1,
                            lastDismissedAt = now,
                            messagePrefix = messagePrefix.ifEmpty { existing.messagePrefix },
                            semanticFingerprint = updatedSemantic,
                            anchorFingerprint = anchorFingerprint.ifEmpty { existing.anchorFingerprint },
                            reason = reason.ifEmpty { existing.reason },
                        )
                    ),
                    insertionOrder = newOrder,
                    semanticIndex = if (updatedSemantic.isNotEmpty()) {
                        s.semanticIndex + (updatedSemantic to fp)
                    } else {
                        s.semanticIndex
                    },
                )
            } else {
                val (newEntries, newOrder, newSemanticIndex) = if (s.entries.size >= maxEntries) {
                    val oldest = s.insertionOrder.first()
                    val evictedEntry = s.entries[oldest]
                    val updatedIndex = if (evictedEntry?.semanticFingerprint?.isNotEmpty() == true) {
                        s.semanticIndex - evictedEntry.semanticFingerprint
                    } else {
                        s.semanticIndex
                    }
                    Triple(s.entries - oldest, s.insertionOrder.drop(1) + fp, updatedIndex)
                } else {
                    Triple(s.entries, s.insertionOrder + fp, s.semanticIndex)
                }
                val updatedSemanticIndex = if (semanticFingerprint.isNotEmpty()) {
                    newSemanticIndex + (semanticFingerprint to fp)
                } else {
                    newSemanticIndex
                }
                s.copy(
                    entries = newEntries + (
                        fp to DismissedEntry(
                            fingerprint = fp,
                            rule = rule,
                            filePath = filePath,
                            messageHash = messageHash,
                            messagePrefix = messagePrefix,
                            semanticFingerprint = semanticFingerprint,
                            anchorFingerprint = anchorFingerprint,
                            reason = reason,
                            dismissCount = 1,
                            firstDismissedAt = now,
                            lastDismissedAt = now,
                        )
                    ),
                    insertionOrder = newOrder,
                    semanticIndex = updatedSemanticIndex,
                )
            }
        }
    }

    /**
     * Classifies a human [reply] to the posted finding [ref] with [ReplyClassifier] and records the verdict:
     * [ReplyVerdict.REJECTED] dismisses the finding (keyed by its message hash and, when the marker has one, its
     * `fpv`), [ReplyVerdict.ACCEPTED] marks its `fpv` accepted, [ReplyVerdict.UNKNOWN] records nothing.
     * A marker without `fpv` (empty), so an acceptance of it is not recorded and it stays unaccepted.
     */
    fun recordReply(
        ref: InlineFindingRef,
        reply: String,
        verdict: ReplyVerdict = ReplyClassifier.classify(reply),
    ): ReplyVerdict {
        when (verdict) {
            ReplyVerdict.REJECTED -> dismiss(ref, REPLY_REASON)
            ReplyVerdict.ACCEPTED -> accept(ref.fpv)
            ReplyVerdict.UNKNOWN -> Unit
        }
        return verdict
    }

    /** Dismisses the posted finding [ref] for [reason] (see [dismiss]). */
    fun dismiss(
        ref: InlineFindingRef,
        reason: String,
    ) = dismiss(ref.rule, ref.path, ref.messageHash, ref.messagePrefix, ref.semanticFingerprint, ref.fpv, reason)

    private fun accept(anchorFingerprint: String) {
        if (anchorFingerprint.isEmpty()) return
        state.update { s -> s.copy(accepted = (s.accepted - anchorFingerprint + anchorFingerprint).takeLast(maxEntries)) }
    }

    /**
     * True when a reviewer accepted the finding whose anchor fingerprint is [anchorFingerprint]. Always false for an
     * empty fingerprint: markers without `fpv` cannot be accepted.
     */
    fun isAccepted(anchorFingerprint: String): Boolean = anchorFingerprint in state.value.accepted

    /** Anchor fingerprints of accepted findings, oldest first; round-trips through [fromEntries]. */
    fun acceptedAnchors(): List<String> = state.value.accepted

    /**
     * Returns true if the given (rule, filePath, messageHash) combination has been dismissed.
     *
     * @param rule The rule ID that produced the finding.
     * @param filePath The file path from the finding location, or null for non-file findings.
     * @param messageHash Hash of the finding message.
     */
    fun isDismissed(
        rule: String,
        filePath: String?,
        messageHash: Int
    ): Boolean {
        val fp = fingerprint(rule, filePath, messageHash)
        return state.value.entries.containsKey(fp)
    }

    /**
     * Partitions [findings] into kept and filtered lists based on the current dismissal state.
     *
     * A finding is filtered out when either:
     * - Its exact (rule, location file path, message hash) fingerprint matches a dismissed entry, OR
     * - Its semantic fingerprint (from [FindingFingerprint]) matches a dismissed entry's semantic fingerprint, OR
     * - Its anchor fingerprint (`WellKnownMetadataKeys.ANCHOR_FINGERPRINT`) matches a dismissed entry's.
     *
     * The semantic check catches cases where the message differs only in numbers or whitespace
     * (e.g., "found 5 issues" vs "found 3 issues").
     *
     * @param findings The list of findings to filter.
     * @return A [FeedbackFilterResult] containing kept and filtered sublists.
     */
    fun filterFindings(findings: List<Finding>): FeedbackFilterResult {
        val kept = mutableListOf<Finding>()
        val filtered = mutableListOf<Finding>()
        val snapshot = state.value
        val rejectedAnchors = snapshot.entries.values.mapNotNullTo(mutableSetOf()) { it.anchorFingerprint.ifEmpty { null } }
        for (finding in findings) {
            val filePath = (finding.location as? Location.FileRef)?.path
            val exactMatch = isDismissed(finding.rule, filePath, finding.message.hashCode())
            val semanticMatch = if (!exactMatch) {
                val semFp = FindingFingerprint.generate(finding.rule, finding.location, finding.message)
                snapshot.semanticIndex.containsKey(semFp)
            } else {
                false
            }
            val anchorMatch = finding.metadata[WellKnownMetadataKeys.ANCHOR_FINGERPRINT] in rejectedAnchors
            if (exactMatch || semanticMatch || anchorMatch) {
                filtered.add(finding)
            } else {
                kept.add(finding)
            }
        }
        return FeedbackFilterResult(kept = kept, filtered = filtered)
    }

    /**
     * Returns all dismissed entries for the given rule ID.
     *
     * @param ruleId The rule ID to filter by.
     * @return A list of [DismissedEntry] instances for the specified rule.
     */
    fun dismissedForRule(ruleId: String): List<DismissedEntry> =
        state.value.entries.values
            .filter { it.rule == ruleId }

    /**
     * Returns the total dismissal count grouped by rule ID.
     *
     * @return A map of rule ID to the sum of dismiss counts for that rule.
     */
    fun dismissCountByRule(): Map<String, Int> =
        state.value.entries.values
            .groupBy { it.rule }
            .mapValues { (_, v) -> v.sumOf { it.dismissCount } }

    /**
     * Returns the number of distinct dismissed fingerprints currently in the store.
     */
    fun dismissCount(): Int = state.value.entries.size

    /**
     * Removes all dismissed entries from the store.
     */
    fun clear() {
        state.value = FeedbackState()
    }

    /**
     * Returns the current entries as a list for direct serialization via [DismissedEntry].
     *
     * The returned list can be round-tripped via [fromEntries].
     */
    fun toSerializable(): List<DismissedEntry> =
        state.value.entries.values
            .toList()

    companion object {
        /** Default maximum number of dismissed entries before LRU eviction kicks in. */
        const val DEFAULT_MAX_ENTRIES = 500

        /** Dismissals of one rule's findings at which `ai.feedback.skipRepeatFindingTypes` skips the rule. */
        const val REPEAT_DISMISSAL_THRESHOLD = 2

        /** [DismissedEntry.reason] of a rejection the keyword classifier read from a reply. */
        const val REPLY_REASON = "reply"

        /**
         * Deserializes a [FeedbackStore] from a list of [DismissedEntry] objects.
         *
         * Entries beyond [DEFAULT_MAX_ENTRIES] are silently dropped (oldest first).
         *
         * @param entries The list to load into the store.
         * @param accepted Anchor fingerprints of accepted findings, from [acceptedAnchors].
         * @return A new [FeedbackStore] populated with the provided entries.
         */
        fun fromEntries(
            entries: List<DismissedEntry>,
            accepted: List<String> = emptyList(),
        ): FeedbackStore {
            val store = FeedbackStore()
            val map = mutableMapOf<String, DismissedEntry>()
            val order = mutableListOf<String>()
            val semIndex = mutableMapOf<String, String>()
            for (entry in entries) {
                if (map.size >= store.maxEntries) break
                map[entry.fingerprint] = entry
                order.add(entry.fingerprint)
                if (entry.semanticFingerprint.isNotEmpty()) {
                    semIndex[entry.semanticFingerprint] = entry.fingerprint
                }
            }
            store.state.value = FeedbackState(
                entries = map.toMap(),
                insertionOrder = order.toList(),
                semanticIndex = semIndex.toMap(),
                accepted = accepted.distinct().takeLast(store.maxEntries),
            )
            return store
        }

        /**
         * Creates a stable fingerprint string for the given (rule, filePath, messageHash) tuple.
         *
         * Uses length-prefixed encoding to prevent collisions when [rule] or [filePath]
         * contain delimiter characters (e.g. pipe or colon).
         *
         * Format: `{rule.length}:{rule}:{path.length}:{path}:{messageHash}`
         *
         * @param rule The rule ID.
         * @param filePath The file path, or null for non-file findings.
         * @param messageHash Hash of the finding message.
         * @return A length-prefixed string uniquely identifying the combination.
         */
        fun fingerprint(
            rule: String,
            filePath: String?,
            messageHash: Int,
        ): String {
            val path = filePath ?: ""
            return "${rule.length}:$rule:${path.length}:$path:$messageHash"
        }
    }
}

/**
 * A dismissed finding entry in the [FeedbackStore].
 *
 * @param fingerprint The stable key for this entry (see [FeedbackStore.fingerprint]).
 * @param rule The rule ID that produced the finding.
 * @param filePath The file path from the finding location, or null for non-file findings.
 * @param messageHash Hash of the finding message.
 * @param messagePrefix First [MESSAGE_PREFIX_MAX_LENGTH] characters of the original finding message.
 *   Used to generate negative prompt examples in [PriorFindingsPromptEnricher.dismissedPrompt]. Empty string when
 *   the entry was created without message text (e.g., an entry persisted before message prefixes were stored).
 * @param semanticFingerprint Normalized fingerprint from [FindingFingerprint.generate] that
 *   matches findings with different numbers or whitespace in the message. Empty string when
 *   the entry was recorded without one.
 * @param anchorFingerprint The dismissed finding's `fpv` (`WellKnownMetadataKeys.ANCHOR_FINGERPRINT`); empty for
 *   entries recorded without one.
 * @param reason Why it was dismissed: a thread command (`ignore`, `wontfix`, `resolve`), [FeedbackStore.REPLY_REASON]
 *   for a rejecting reply, empty when recorded without one.
 * @param dismissCount Number of times this finding has been dismissed.
 * @param firstDismissedAt Epoch millis when this fingerprint was first dismissed.
 * @param lastDismissedAt Epoch millis when this fingerprint was most recently dismissed.
 */
@Serializable
data class DismissedEntry(
    val fingerprint: String,
    val rule: String,
    val filePath: String?,
    val messageHash: Int,
    val messagePrefix: String = "",
    val semanticFingerprint: String = "",
    val anchorFingerprint: String = "",
    val reason: String = "",
    val dismissCount: Int = 1,
    val firstDismissedAt: Long,
    val lastDismissedAt: Long,
) {
    companion object {
        /** Maximum length of [messagePrefix] stored per entry. */
        const val MESSAGE_PREFIX_MAX_LENGTH = 100
    }
}

/**
 * Result of filtering a list of findings through a [FeedbackStore].
 *
 * @param kept Findings that were not dismissed and should be reported normally.
 * @param filtered Findings that matched a dismissed fingerprint and were suppressed.
 */
@Serializable
data class FeedbackFilterResult(
    val kept: List<Finding>,
    val filtered: List<Finding>,
) {
    /** Number of findings suppressed by the feedback store. */
    val filteredCount: Int get() = filtered.size
}
