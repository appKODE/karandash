package io.karandash.cli.dangerfile

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.Serializable

/**
 * Parses a DangerResults JSON file into karandash [Finding] objects.
 *
 * DangerResults format:
 * ```json
 * {
 *   "fails": [{"message": "...", "file": "src/foo.kt", "line": 42}],
 *   "warnings": [{"message": "..."}],
 *   "messages": [{"message": "..."}],
 *   "markdowns": [{"message": "# Title\n..."}]
 * }
 * ```
 */
object DangerResultsParser {
    private val json = io.karandash.models.support.KarandashJson.Default

    /**
     * Parse a DangerResults JSON string into a list of [Finding] objects.
     *
     * @param jsonString Raw JSON from the Dangerfile subprocess
     * @return Findings mapped from the DangerResults violations
     */
    fun parse(jsonString: String): List<Finding> {
        val dto = json.decodeFromString<DangerResultsDto>(jsonString)
        return buildList {
            dto.fails.mapTo(this) { it.toFinding(Severity.ERROR) }
            dto.warnings.mapTo(this) { it.toFinding(Severity.WARNING) }
            dto.messages.mapTo(this) { it.toFinding(Severity.INFO) }
            dto.markdowns.mapTo(this) { it.toFinding(Severity.INFO, isMarkdown = true) }
        }
    }

    private fun DangerViolation.toFinding(
        severity: Severity,
        isMarkdown: Boolean = false
    ): Finding {
        val location = if (file != null) Location.FileRef(path = file, line = line) else null
        val metadata = if (isMarkdown) mapOf("markdown" to "true") else emptyMap()
        return Finding(
            severity = severity,
            message = message,
            rule = "dangerfile",
            location = location,
            metadata = metadata
        )
    }
}

@Serializable
private data class DangerViolation(
    val message: String,
    val file: String? = null,
    val line: Int? = null,
)

@Serializable
private data class DangerResultsDto(
    val fails: List<DangerViolation> = emptyList(),
    val warnings: List<DangerViolation> = emptyList(),
    val messages: List<DangerViolation> = emptyList(),
    val markdowns: List<DangerViolation> = emptyList(),
)
