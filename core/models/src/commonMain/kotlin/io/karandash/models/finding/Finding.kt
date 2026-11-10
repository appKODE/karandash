package io.karandash.models.finding

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

@Serializable
data class Finding(
    val severity: Severity,
    val message: String,
    val rule: String,
    val location: Location? = null,
    val explanation: String? = null,
    val suggestion: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val fixSuggestions: List<FixSuggestion> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val fingerprint: String? = null,
    val priority: Int = DEFAULT_PRIORITY,
    val confidence: Float? = null,
    val relatedFindings: List<String> = emptyList(),
    val category: FindingCategory? = null,
    val errorCode: String? = null,
) {
    companion object {
        /** Default priority value (mid-range). Lower numbers = higher priority. */
        const val DEFAULT_PRIORITY = 100
    }
}

/**
 * A named content attachment on a [Finding].
 *
 * Used for rich report data (heatmaps, HTML diffs, compliance packs) that
 * reporters render in their native format: console prints inline, Slack uploads
 * as file, webhook includes in payload, comment poster attaches to MR.
 *
 * @param filename Suggested filename (e.g., "risk-heatmap.md", "annotated-diff.html").
 * @param content The full text content of the attachment.
 * @param contentType MIME type hint: "text/markdown", "text/html", "application/json".
 */
@Serializable
data class Attachment(
    val filename: String,
    val content: String,
    val contentType: String = "text/markdown",
)

/**
 * Structured fix suggestion that a rule emits alongside a [Finding].
 * The auto-fixer processes these generically without inspecting rule IDs or message text.
 */
@Serializable
sealed class FixSuggestion {
    /** Add a label to the merge request. */
    @Serializable
    @SerialName("add-label")
    data class AddLabel(
        val label: String,
    ) : FixSuggestion()

    /** Remove a label from the merge request. */
    @Serializable
    @SerialName("remove-label")
    data class RemoveLabel(
        val label: String,
    ) : FixSuggestion()

    /** Set or unset the draft/WIP status on the merge request. */
    @Serializable
    @SerialName("set-draft")
    data class SetDraft(
        val draft: Boolean,
    ) : FixSuggestion()

    /** Update the merge request title. */
    @Serializable
    @SerialName("set-title")
    data class SetTitle(
        val title: String,
    ) : FixSuggestion()

    /** Update the merge request description. */
    @Serializable
    @SerialName("set-description")
    data class SetDescription(
        val description: String,
    ) : FixSuggestion()

    /**
     * A plugin-defined fix suggestion. Plugins can use this to suggest custom fixes
     * that are handled by registered fix handler implementations.
     *
     * @param type A unique identifier for this fix type (e.g., "update-dependency-version").
     *     Serialized as `fixType`, because `type` is the sealed-class discriminator.
     * @param data Arbitrary JSON data for the fix handler.
     * @param description Human-readable description of the fix action.
     */
    @Serializable
    @SerialName("custom")
    data class Custom(
        @SerialName("fixType")
        val type: String,
        val data: JsonElement = JsonNull,
        val description: String = "",
    ) : FixSuggestion()
}

@Serializable
sealed class Location {
    /** A location within a source file, optionally at a specific line. */
    @Serializable
    @SerialName("file")
    data class FileRef(
        val path: String,
        val line: Int? = null,
        val endLine: Int? = null,
    ) : Location()

    /** A reference to a specific commit by its SHA hash. */
    @Serializable
    @SerialName("commit")
    data class CommitRef(
        val sha: String,
    ) : Location()
}
