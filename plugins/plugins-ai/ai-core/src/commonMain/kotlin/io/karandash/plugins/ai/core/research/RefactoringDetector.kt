package io.karandash.plugins.ai.core.research

import io.karandash.models.FileChange
import kotlinx.serialization.Serializable

/**
 * Refactoring-aware diff analysis.
 * Separates refactoring noise from behavioral changes.
 *
 * Detects common refactoring patterns:
 * - Renames (same filename, different directory)
 * - Move class (file deleted + similar file added elsewhere)
 * - Import reorganization
 * - Whitespace/formatting-only changes
 */
object RefactoringDetector {
    /**
     * Analyzes [changes] to classify each file as a refactoring pattern or a behavioral change.
     *
     * @param changes The list of file changes from the MR.
     * @return [RefactoringAnalysis] with detected patterns and unclassified behavioral changes.
     */
    fun analyze(changes: List<FileChange>): RefactoringAnalysis {
        val refactoring = mutableListOf<RefactoringPattern>()
        val behavioral = mutableListOf<FileChange>()

        for (change in changes) {
            val diff = change.diffContent ?: run {
                behavioral.add(change)
                continue
            }
            val pattern = detectPattern(change, diff, changes)
            if (pattern != null) {
                refactoring.add(pattern)
            } else {
                behavioral.add(change)
            }
        }

        val refactoringRatio = if (changes.isNotEmpty()) {
            refactoring.size.toDouble() / changes.size
        } else {
            0.0
        }

        return RefactoringAnalysis(
            refactoringPatterns = refactoring,
            behavioralChanges = behavioral,
            refactoringRatio = refactoringRatio,
            isRefactoringOnly = behavioral.isEmpty() && refactoring.isNotEmpty(),
            isMixed = refactoring.isNotEmpty() && behavioral.isNotEmpty(),
        )
    }

    private fun detectPattern(
        change: FileChange,
        diff: String,
        allChanges: List<FileChange>,
    ): RefactoringPattern? {
        val lines = diff.lines()
        val added = lines.filter { it.startsWith("+") && !it.startsWith("+++") }
        val removed = lines.filter { it.startsWith("-") && !it.startsWith("---") }

        // Import-only changes: every added/removed line is an import or blank,
        // and at least one actual import line is present.
        val hasImport = (added + removed).any { line ->
            line
                .removePrefix("+")
                .removePrefix("-")
                .trim()
                .startsWith("import ")
        }
        if (hasImport && added.all { isImportLine(it) } && removed.all { isImportLine(it) }) {
            return RefactoringPattern(
                type = RefactoringType.IMPORT_REORG,
                file = change.path,
                description = "Import reorganization",
            )
        }

        // Move/rename detection: file deleted + same filename added elsewhere.
        if (change.changeType.name == "DELETED") {
            val similar = allChanges.find {
                it.changeType.name == "ADDED" &&
                    it.path.substringAfterLast('/') == change.path.substringAfterLast('/')
            }
            if (similar != null) {
                return RefactoringPattern(
                    type = RefactoringType.MOVE_CLASS,
                    file = change.path,
                    description = "Moved to ${similar.path}",
                )
            }
        }

        // Whitespace/formatting-only changes.
        val substantiveAdded = added.count { it.removePrefix("+").isNotBlank() }
        val substantiveRemoved = removed.count { it.removePrefix("-").isNotBlank() }
        if (substantiveAdded == 0 && substantiveRemoved == 0 && lines.isNotEmpty()) {
            return RefactoringPattern(
                type = RefactoringType.FORMATTING,
                file = change.path,
                description = "Whitespace/formatting only",
            )
        }

        return null
    }

    private fun isImportLine(line: String): Boolean {
        val content = line.removePrefix("+").removePrefix("-").trim()
        return content.startsWith("import ") || content.isEmpty()
    }
}

/**
 * Result of refactoring analysis over a set of file changes.
 *
 * @param refactoringPatterns Detected refactoring patterns with type and description.
 * @param behavioralChanges File changes that could not be classified as pure refactoring.
 * @param refactoringRatio Fraction of changes classified as refactoring (0.0–1.0).
 * @param isRefactoringOnly True when every change is a refactoring pattern (no behavioral changes).
 * @param isMixed True when both refactoring patterns and behavioral changes are present.
 */
@Serializable
data class RefactoringAnalysis(
    val refactoringPatterns: List<RefactoringPattern>,
    val behavioralChanges: List<FileChange>,
    val refactoringRatio: Double,
    val isRefactoringOnly: Boolean,
    val isMixed: Boolean,
)

/**
 * A single detected refactoring pattern.
 *
 * @param type The category of refactoring.
 * @param file The file path where the pattern was observed.
 * @param description Human-readable description of the pattern.
 */
@Serializable
data class RefactoringPattern(
    val type: RefactoringType,
    val file: String,
    val description: String,
)

/** Categories of refactoring patterns detected by [RefactoringDetector]. */
enum class RefactoringType {
    RENAME,
    EXTRACT_METHOD,
    MOVE_CLASS,
    IMPORT_REORG,
    FORMATTING,
}
