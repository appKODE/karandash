package io.karandash.api.finding

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.support.KarandashJson
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.utils.finding.FindingFingerprint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * Identity of one finding inside a posted inline comment, so a reviewer's reaction to the comment
 * (`PlatformServiceModule.listDismissedInlineComments`) can be traced back to the finding.
 *
 * @property fpv The finding's anchor fingerprint (`WellKnownMetadataKeys.ANCHOR_FINGERPRINT`); empty when the finding
 *   has none, and [InlineThreadMatcher] then matches by [semanticFingerprint] or [messagePrefix].
 */
@Serializable
public data class InlineFindingRef(
    val rule: String,
    val path: String? = null,
    @SerialName("hash") val messageHash: Int,
    @SerialName("fp") val semanticFingerprint: String = "",
    @SerialName("msg") val messagePrefix: String = "",
    val fpv: String = "",
)

/** Hidden `<!-- karandash-finding {json} -->` line a formatter appends per finding to an inline comment body. */
public object InlineFindingMarker {
    /** Start of every marker line; platforms search comment bodies for it. */
    public const val PREFIX: String = "<!-- karandash-finding "

    /**
     * Start of the hidden `<!-- karandash-row <fp> -->` a summary table row ends with: the `fp` of the finding the row
     * shows (its [InlineFindingRef.semanticFingerprint]), so an output can drop the rows an inline thread already shows.
     */
    public const val ROW_PREFIX: String = "<!-- karandash-row "
    private const val SUFFIX = " -->"
    private const val MESSAGE_PREFIX_LENGTH = 80

    /** The marker line for [finding]; both `-->` and `--!>` (HTML closes a comment on either) are escaped inside the JSON. */
    public fun of(finding: Finding): String =
        PREFIX +
            KarandashJson.Default
                .encodeToString(
                    InlineFindingRef.serializer(),
                    refOf(finding)
                ).replace("-->", "--\\u003e")
                .replace("--!>", "--!\\u003e") +
            SUFFIX

    /** The ref [of] writes for [finding]. */
    public fun refOf(finding: Finding): InlineFindingRef =
        InlineFindingRef(
            rule = finding.rule,
            path = (finding.location as? Location.FileRef)?.path,
            messageHash = finding.message.hashCode(),
            semanticFingerprint = FindingFingerprint.generate(finding.rule, finding.location, finding.message),
            messagePrefix = messagePrefixOf(finding.message),
            fpv = finding.metadata[WellKnownMetadataKeys.ANCHOR_FINGERPRINT].orEmpty(),
        )

    // "--" would end the HTML comment early
    internal fun messagePrefixOf(message: String): String = message.take(MESSAGE_PREFIX_LENGTH).replace("--", "- ")

    /** Whether [body] carries a marker line, i.e. is a note karandash wrote. */
    public fun carriesFindings(body: String): Boolean = PREFIX in body

    /** Every finding marked in [body]; unreadable markers are skipped. */
    public fun parse(body: String): List<InlineFindingRef> =
        body
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith(PREFIX) && it.endsWith(SUFFIX) }
            .mapNotNull { line ->
                try {
                    KarandashJson.Default.decodeFromString(InlineFindingRef.serializer(), line.removePrefix(PREFIX).removeSuffix(SUFFIX))
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }.toList()
}
