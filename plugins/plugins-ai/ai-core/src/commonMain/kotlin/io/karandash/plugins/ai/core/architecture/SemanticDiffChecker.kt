package io.karandash.plugins.ai.core.architecture

import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity

/**
 * Semantic diff invariant checking.
 *
 * Detects public API breakage by analyzing diff content for:
 * - Removed/renamed public functions, classes, interfaces
 * - Removed public fields/properties
 *
 * Uses regex-based heuristics rather than full AST parsing for KMP compatibility.
 */
object SemanticDiffChecker {
    /** Rule ID emitted in all findings produced by this checker. */
    const val RULE_ID = "semantic-diff:api-breakage"

    private val REMOVED_PUBLIC_FUNC = Regex(
        """^-\s*(public\s+|open\s+|)fun\s+(\w+)\s*\(""",
        RegexOption.MULTILINE,
    )
    private val REMOVED_PUBLIC_CLASS = Regex(
        """^-\s*(public\s+|open\s+|)(class|interface|object|enum class)\s+(\w+)""",
        RegexOption.MULTILINE,
    )
    private val REMOVED_PUBLIC_PROPERTY = Regex(
        """^-\s*(public\s+|open\s+|)(val|var)\s+(\w+)""",
        RegexOption.MULTILINE,
    )
    private val ADDED_FUNC = Regex(
        """^\+\s*(public\s+|open\s+|)fun\s+(\w+)\s*\(""",
        RegexOption.MULTILINE,
    )
    private val NON_PUBLIC_MODIFIER = Regex("""(private|internal|protected)\s+""")

    /**
     * Checks for public API breakage in the given file changes.
     *
     * @param changes List of file changes from the MR.
     * @return List of findings for potential API breakage; empty when no breakage detected.
     */
    fun check(changes: List<FileChange>): List<Finding> {
        val findings = mutableListOf<Finding>()

        for (change in changes) {
            val diff = change.diffContent ?: continue

            findings += checkRemovedFunctions(diff, change)
            findings += checkRemovedClassLike(diff, change)
            findings += checkRemovedProperties(diff, change)
        }

        return findings
    }

    private fun checkRemovedFunctions(
        diff: String,
        change: FileChange
    ): List<Finding> {
        val addedFuncNames = ADDED_FUNC.findAll(diff).map { it.groupValues[2] }.toSet()
        return REMOVED_PUBLIC_FUNC
            .findAll(diff)
            .mapNotNull { match ->
                val modifier = match.groupValues[1].trim()
                if (NON_PUBLIC_MODIFIER.containsMatchIn(modifier)) return@mapNotNull null
                val name = match.groupValues[2]
                if (name in addedFuncNames) return@mapNotNull null
                Finding(
                    severity = Severity.WARNING,
                    message = "Public function '$name' removed — potential API breakage",
                    rule = RULE_ID,
                    location = Location.FileRef(change.path),
                )
            }.toList()
    }

    private fun checkRemovedClassLike(
        diff: String,
        change: FileChange
    ): List<Finding> =
        REMOVED_PUBLIC_CLASS
            .findAll(diff)
            .mapNotNull { match ->
                val modifier = match.groupValues[1].trim()
                if (NON_PUBLIC_MODIFIER.containsMatchIn(modifier)) return@mapNotNull null
                val kind = match.groupValues[2]
                val name = match.groupValues[3]
                Finding(
                    severity = Severity.ERROR,
                    message = "Public $kind '$name' removed — breaking API change",
                    rule = RULE_ID,
                    location = Location.FileRef(change.path),
                )
            }.toList()

    private fun checkRemovedProperties(
        diff: String,
        change: FileChange
    ): List<Finding> =
        REMOVED_PUBLIC_PROPERTY
            .findAll(diff)
            .mapNotNull { match ->
                val modifier = match.groupValues[1].trim()
                if (NON_PUBLIC_MODIFIER.containsMatchIn(modifier)) return@mapNotNull null
                val name = match.groupValues[3]
                Finding(
                    severity = Severity.WARNING,
                    message = "Public property '$name' removed — potential API breakage",
                    rule = RULE_ID,
                    location = Location.FileRef(change.path),
                )
            }.toList()
}
