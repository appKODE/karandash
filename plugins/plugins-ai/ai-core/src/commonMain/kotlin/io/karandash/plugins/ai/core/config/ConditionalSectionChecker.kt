package io.karandash.plugins.ai.core.config

import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.utils.GlobMatcher
import kotlinx.serialization.Serializable

/**
 * Conditional description sections: require sections based on changed files.
 * E.g., `## Database Changes` required only when `.sql` files change.
 */
object ConditionalSectionChecker {
    private const val RULE_ID = "description-format:conditional-section"

    /**
     * Checks if required conditional sections are present in the MR description.
     *
     * @param mr The merge request to check.
     * @param sections The list of conditional section configurations.
     * @return List of findings for each missing required section.
     */
    fun check(
        mr: MergeRequest,
        sections: List<ConditionalSection>
    ): List<Finding> {
        val description = mr.description ?: ""
        val changedPaths = mr.changes.map { it.path }

        return sections.mapNotNull { section ->
            val triggered = section.whenFilesChanged.any { pattern ->
                changedPaths.any { path -> GlobMatcher.matches(path, pattern) }
            }
            if (!triggered) return@mapNotNull null

            val wanted = headingText(section.section)
            if (description.lines().any { headingText(it).equals(wanted, ignoreCase = true) }) return@mapNotNull null

            val kind = if (section.required) "required" else "recommended"
            Finding(
                severity = if (section.required) Severity.fromString(section.severity, default = Severity.WARNING) else Severity.INFO,
                message = "Missing $kind section '${section.section}' — $kind when " +
                    "${section.whenFilesChanged.joinToString(", ")} files change" +
                    (section.description?.let { ": $it" } ?: ""),
                rule = RULE_ID,
            )
        }
    }

    /** A line as heading text: trimmed, without a leading markdown `#` marker, so `## Database` and `Database` agree. */
    private fun headingText(line: String): String = line.trim().trimStart('#').trim()
}

/** A section that is conditionally required in the MR description based on changed files. */
@Serializable
data class ConditionalSection(
    /**
     * The section heading that must appear in the MR description as a line of its own, matched case-insensitively and
     * with or without its leading `#` marker; the same words mid-sentence do not count.
     */
    val section: String,
    /** Glob patterns - when any matched file is changed, this section becomes required. */
    val whenFilesChanged: List<String> = emptyList(),
    /** Severity of the finding when the section is missing. Defaults to `warning`. */
    val severity: String = "warning",
    /** False makes the section optional: a missing one yields an INFO finding instead of [severity]. */
    val required: Boolean = true,
    /** What the section should contain; appended to the missing-section message. */
    val description: String? = null,
)
