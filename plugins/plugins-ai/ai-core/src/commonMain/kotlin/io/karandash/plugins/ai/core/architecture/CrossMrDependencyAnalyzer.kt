package io.karandash.plugins.ai.core.architecture

import io.karandash.models.ChangeType
import io.karandash.models.MergeRequest
import kotlinx.serialization.Serializable

/**
 * Cross-MR dependency graph validation.
 *
 * Given a set of open MRs targeting the same branch, detects:
 * - File conflicts (multiple MRs modify the same file)
 * - Ordering constraints (MR A deletes/renames files that MR B modifies)
 * - Circular dependencies between MRs
 */
object CrossMrDependencyAnalyzer {
    /**
     * Analyzes dependencies between sibling MRs.
     *
     * @param currentMr The MR being validated.
     * @param siblingMrs Other open MRs targeting the same branch.
     * @return Analysis result with conflicts and dependency edges.
     */
    fun analyze(
        currentMr: MergeRequest,
        siblingMrs: List<MergeRequest>
    ): CrossMrAnalysis {
        val currentFiles = currentMr.changes.map { it.path }.toSet()
        val conflicts = mutableListOf<FileConflict>()
        val dependencies = mutableListOf<MrDependency>()

        for (sibling in siblingMrs) {
            if (sibling.id == currentMr.id) continue
            val siblingFiles = sibling.changes.map { it.path }.toSet()
            val overlapping = currentFiles.intersect(siblingFiles)

            if (overlapping.isNotEmpty()) {
                conflicts.add(
                    FileConflict(
                        mrIdA = currentMr.id,
                        mrIdB = sibling.id,
                        mrTitleB = sibling.title,
                        conflictingFiles = overlapping.toList(),
                    ),
                )
            }

            val currentDeletedOrRenamed = currentMr.changes
                .filter { it.changeType == ChangeType.DELETED || it.changeType == ChangeType.RENAMED }
                .map { it.path }
                .toSet()
            val affectedBySibling = currentDeletedOrRenamed.intersect(siblingFiles)
            if (affectedBySibling.isNotEmpty()) {
                dependencies.add(
                    MrDependency(
                        fromMrId = currentMr.id,
                        toMrId = sibling.id,
                        reason = "Current MR deletes/renames files that MR '${sibling.title}' modifies",
                        affectedFiles = affectedBySibling.toList(),
                    ),
                )
            }
        }

        val hasCircular = detectCircular(dependencies)

        return CrossMrAnalysis(
            conflicts = conflicts,
            dependencies = dependencies,
            hasCircularDependency = hasCircular,
            siblingCount = siblingMrs.size,
        )
    }

    /**
     * Analyzes every MR in [mrs] against every other one (the full pairwise matrix): each overlapping pair is
     * reported once as a conflict, every ordering edge in either direction is kept, and cycles are detected
     * across all of them.
     */
    fun analyzeAll(mrs: List<MergeRequest>): CrossMrAnalysis {
        val conflicts = mrs.flatMapIndexed { i, mr -> analyze(mr, mrs.drop(i + 1)).conflicts }
        val dependencies = mrs.flatMap { mr -> analyze(mr, mrs).dependencies }
        return CrossMrAnalysis(
            conflicts = conflicts,
            dependencies = dependencies,
            hasCircularDependency = detectCircular(dependencies),
            siblingCount = (mrs.size - 1).coerceAtLeast(0),
        )
    }

    private fun detectCircular(dependencies: List<MrDependency>): Boolean {
        val graph = mutableMapOf<String, MutableSet<String>>()
        for (dep in dependencies) {
            graph.getOrPut(dep.fromMrId) { mutableSetOf() }.add(dep.toMrId)
        }

        val visited = mutableSetOf<String>()
        val stack = mutableSetOf<String>()

        fun hasCycle(node: String): Boolean {
            if (node in stack) return true
            if (node in visited) return false
            visited.add(node)
            stack.add(node)
            for (neighbor in graph[node].orEmpty()) {
                if (hasCycle(neighbor)) return true
            }
            stack.remove(node)
            return false
        }

        return graph.keys.any { hasCycle(it) }
    }

    /**
     * Formats a human-readable report of the analysis result.
     *
     * @param analysis The result produced by [analyze] or [analyzeAll].
     * @param showBothSides Name both MRs of each conflict (for [analyzeAll], where the first is not always the current MR).
     * @return A markdown-formatted report string.
     */
    fun formatReport(
        analysis: CrossMrAnalysis,
        showBothSides: Boolean = false,
    ): String =
        buildString {
            appendLine("## Cross-MR Dependency Analysis")
            appendLine("Sibling MRs: ${analysis.siblingCount}")
            if (analysis.conflicts.isEmpty() && analysis.dependencies.isEmpty()) {
                appendLine("No conflicts or dependencies detected.")
                return@buildString
            }
            if (analysis.conflicts.isNotEmpty()) {
                appendLine("\n### File Conflicts")
                for (c in analysis.conflicts) {
                    val pair = if (showBothSides) "MR ${c.mrIdA} ↔ " else ""
                    appendLine("- ${pair}MR ${c.mrIdB} ('${c.mrTitleB}'): ${c.conflictingFiles.joinToString(", ")}")
                }
            }
            if (analysis.dependencies.isNotEmpty()) {
                appendLine("\n### Dependencies")
                for (d in analysis.dependencies) {
                    appendLine("- ${d.fromMrId} → ${d.toMrId}: ${d.reason}")
                }
            }
            if (analysis.hasCircularDependency) {
                appendLine("\n**WARNING: Circular dependency detected!**")
            }
        }
}

/**
 * Result of a cross-MR dependency analysis.
 *
 * @param conflicts Files modified by both the current MR and a sibling MR.
 * @param dependencies Ordering constraints between MRs.
 * @param hasCircularDependency Whether a dependency cycle was detected.
 * @param siblingCount Total number of sibling MRs examined (including those with no overlap).
 */
@Serializable
data class CrossMrAnalysis(
    val conflicts: List<FileConflict>,
    val dependencies: List<MrDependency>,
    val hasCircularDependency: Boolean,
    val siblingCount: Int,
)

/**
 * A file-level conflict between two MRs.
 *
 * @param mrIdA The current MR's identifier.
 * @param mrIdB The sibling MR's identifier.
 * @param mrTitleB The sibling MR's title.
 * @param conflictingFiles Paths modified by both MRs.
 */
@Serializable
data class FileConflict(
    val mrIdA: String,
    val mrIdB: String,
    val mrTitleB: String,
    val conflictingFiles: List<String>,
)

/**
 * An ordering constraint between two MRs.
 *
 * @param fromMrId The MR that must be applied first (or causes the constraint).
 * @param toMrId The MR affected by the constraint.
 * @param reason Human-readable explanation of why the constraint exists.
 * @param affectedFiles Files that create the dependency.
 */
@Serializable
data class MrDependency(
    val fromMrId: String,
    val toMrId: String,
    val reason: String,
    val affectedFiles: List<String>,
)
