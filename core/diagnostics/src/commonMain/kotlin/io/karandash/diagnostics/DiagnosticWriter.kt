package io.karandash.diagnostics

import io.karandash.models.support.KarandashJson
import io.karandash.platform.createDirectories
import io.karandash.platform.safeParent
import io.karandash.platform.writeText
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.encodeToString

/**
 * Writes a [DiagnosticReport] to a JSON file, creating parent directories as needed.
 */
object DiagnosticWriter {
    /**
     * Serialize [report] to JSON and write to [outputPath].
     * Creates parent directories if they do not exist.
     */
    fun write(
        outputPath: String,
        report: DiagnosticReport
    ) {
        val path = Path(outputPath)
        val parent = path.safeParent
        if (parent != null) {
            createDirectories(parent)
        }
        val json = KarandashJson.Default.encodeToString(report)
        path.writeText(json)
    }
}
