package io.karandash.plugins.ai.core.reporters

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import kotlinx.serialization.Serializable

/**
 * Risk heatmap: computes finding density per file and directory.
 *
 * Weights findings by severity ([ERROR_WEIGHT] for errors, [WARNING_WEIGHT] for warnings,
 * INFO findings contribute zero weight). Output can be rendered as SVG/HTML by reporters.
 */
object RiskHeatmapGenerator {
    private const val ERROR_WEIGHT = 3
    private const val WARNING_WEIGHT = 1
    private const val TOP_FILES_COUNT = 10
    private const val TOP_DIRS_COUNT = 5
    private const val BAR_MAX_BLOCKS = 10

    /**
     * Generates a [RiskHeatmap] from the given findings.
     *
     * Only findings with a [Location.FileRef] location contribute to the heatmap;
     * findings with other location types (e.g. [Location.CommitRef]) are ignored.
     *
     * @param findings Validation findings to analyse.
     * @return A [RiskHeatmap] with files and directories sorted by risk score descending.
     */
    fun generate(findings: List<Finding>): RiskHeatmap {
        val fileScores = mutableMapOf<String, Int>()
        val dirScores = mutableMapOf<String, Int>()

        for (finding in findings) {
            val path = (finding.location as? Location.FileRef)?.path ?: continue
            val weight = when (finding.severity) {
                Severity.ERROR -> ERROR_WEIGHT
                Severity.WARNING -> WARNING_WEIGHT
                Severity.INFO -> 0
            }
            fileScores[path] = (fileScores[path] ?: 0) + weight

            val dir = path.substringBeforeLast('/')
            if (dir != path) {
                dirScores[dir] = (dirScores[dir] ?: 0) + weight
            }
        }

        val maxFileScore = fileScores.values.maxOrNull() ?: 0

        val fileEntries = fileScores
            .map { (path, score) ->
                HeatmapEntry(
                    path = path,
                    score = score,
                    normalizedScore = if (maxFileScore > 0) score.toDouble() / maxFileScore else 0.0,
                    findingCount = findings.count { (it.location as? Location.FileRef)?.path == path },
                )
            }.sortedByDescending { it.score }

        val dirEntries = dirScores
            .map { (path, score) ->
                HeatmapEntry(path = path, score = score, normalizedScore = 0.0, findingCount = 0)
            }.sortedByDescending { it.score }

        return RiskHeatmap(files = fileEntries, directories = dirEntries)
    }

    /**
     * Formats a [RiskHeatmap] as a human-readable Markdown report.
     *
     * Shows up to [TOP_FILES_COUNT] files and [TOP_DIRS_COUNT] directories.
     *
     * @param heatmap The heatmap to format.
     * @param language `report.language` of the fixed texts; null is English.
     * @return A Markdown string with risk score tables.
     */
    fun formatReport(
        heatmap: RiskHeatmap,
        language: String? = null,
    ): String =
        buildString {
            appendLine(AiReportText.HEATMAP_TITLE.of(language))
            appendLine()
            appendLine(AiReportText.HEATMAP_FILES.of(language))
            appendLine(AiReportText.HEATMAP_HEADER.of(language))
            appendLine("|------|-----------|----------|")
            for (entry in heatmap.files.take(TOP_FILES_COUNT)) {
                val bar = "█".repeat((entry.normalizedScore * BAR_MAX_BLOCKS).toInt().coerceAtLeast(1))
                appendLine("| ${entry.path} | $bar ${entry.score} | ${entry.findingCount} |")
            }
            if (heatmap.directories.isNotEmpty()) {
                appendLine()
                appendLine(AiReportText.HEATMAP_DIRECTORIES.of(language))
                for (entry in heatmap.directories.take(TOP_DIRS_COUNT)) {
                    appendLine(AiReportText.HEATMAP_DIRECTORY.of(language, entry.path, entry.score))
                }
            }
        }
}

/**
 * Risk heatmap grouping finding density by file and directory.
 *
 * @param files Per-file heatmap entries sorted by risk score descending.
 * @param directories Per-directory heatmap entries sorted by risk score descending.
 */
@Serializable
data class RiskHeatmap(
    val files: List<HeatmapEntry>,
    val directories: List<HeatmapEntry>,
)

/**
 * A single entry in a [RiskHeatmap].
 *
 * @param path File or directory path.
 * @param score Weighted risk score (errors count [ERROR_WEIGHT]x, warnings [WARNING_WEIGHT]x).
 * @param normalizedScore Score normalized to [0.0, 1.0] relative to the highest-scoring file.
 *   Always 0.0 for directory entries.
 * @param findingCount Raw number of findings at this path (unweighted). Always 0 for directories.
 */
@Serializable
data class HeatmapEntry(
    val path: String,
    val score: Int,
    val normalizedScore: Double,
    val findingCount: Int,
)
