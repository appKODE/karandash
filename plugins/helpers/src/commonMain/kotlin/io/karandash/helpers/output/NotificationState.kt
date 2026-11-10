package io.karandash.helpers.output

import io.karandash.api.finding.CommentMarkers
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** When an output destination delivers: the `when` key of the platform summary, Slack, email and webhook outputs. */
@Serializable
enum class NotifyWhen {
    /** Every run. */
    @SerialName("always")
    ALWAYS,

    /**
     * Only when the verdict or the set of findings differs from the last post (the platform summary also counts a
     * changed text unless its `updateOnTextChange` is `false`).
     */
    @SerialName("changed")
    CHANGED,

    /** Only when the run failed. */
    @SerialName("failed")
    FAILED,

    /** Never. */
    @SerialName("never")
    NEVER,
    ;

    /** Whether to deliver a run that [passed]; [changed] is `null` when the last post is unknown (a local run): deliver. */
    fun allows(
        passed: Boolean,
        changed: Boolean?,
    ): Boolean =
        when (this) {
            ALWAYS -> true
            CHANGED -> changed != false
            FAILED -> !passed
            NEVER -> false
        }
}

/**
 * What the platform summary comment last told the MR, kept in an invisible marker line of that comment
 * ([toMarker], read back with [parse]) so the next run can tell whether anything changed. No other state store.
 *
 * @property passed The verdict.
 * @property findings Hash of the sorted finding fingerprints.
 * @property body Hash of the summary body without the marker; empty until the body is known.
 */
@Serializable
data class NotificationState(
    val passed: Boolean,
    val findings: String,
    val body: String = "",
) {
    /** Same verdict and same findings as [other]; `false` when [other] is unknown. */
    fun sameOutcome(other: NotificationState?): Boolean = other != null && other.passed == passed && other.findings == findings

    /** The marker line: an HTML comment, invisible where the platform renders markdown. */
    fun toMarker(): String = "$PREFIX verdict=${if (passed) PASS else FAIL} findings=$findings body=$body -->"

    companion object {
        /** Start of the marker line; finds the comment that carries it. */
        const val PREFIX = CommentMarkers.SUMMARY_STATE

        private const val PASS = "pass"
        private const val FAIL = "fail"
        private val MARKER = Regex("""${Regex.escape(PREFIX)} verdict=(pass|fail) findings=([0-9a-f]+) body=([0-9a-f]*) -->""")
        private const val FNV_OFFSET = -0x340d631b7bdddcdbL
        private const val FNV_PRIME = 0x100000001b3L
        private const val HEX = 16

        /** The state of a run that [passed] with findings of these [fingerprints] (order does not matter). */
        fun of(
            passed: Boolean,
            fingerprints: Collection<String>,
        ): NotificationState = NotificationState(passed, hash(fingerprints.sorted().joinToString("\n")))

        /** The last state marker in [text], or null when it carries none. */
        fun parse(text: String?): NotificationState? {
            val match = text?.let { MARKER.findAll(it).lastOrNull() } ?: return null
            val (verdict, findings, body) = match.destructured
            return NotificationState(verdict == PASS, findings, body)
        }

        /** FNV-1a 64 of [text], in hex: stable across runs and targets. */
        fun hash(text: String): String =
            text
                .fold(FNV_OFFSET) { acc, c -> (acc xor c.code.toLong()) * FNV_PRIME }
                .toULong()
                .toString(HEX)
    }
}
