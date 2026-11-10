package io.karandash.enrichment.ci

import io.karandash.platform.exists
import io.karandash.platform.isDirectory
import io.karandash.platform.isFile
import io.karandash.platform.listFiles
import io.karandash.platform.readText
import io.karandash.platform.safeName
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.utils.GlobMatcher
import kotlinx.io.files.Path

/**
 * Scans the working directory for coverage artifacts whose path (relative to the working
 * directory) matches one of `coverage-gaps.artifact-patterns`, parses them (`.xml` as JaCoCo,
 * anything else as LCOV), and returns uncovered ranges for the files changed in the MR.
 */
internal object CiCoverageScanner {
    private const val MAX_SCAN_DEPTH = 8
    private const val MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024 // 5 MB

    fun scan(
        workingDir: String,
        changedPaths: List<String>,
        patterns: List<String> = CiCoverageGapsSubConfig().artifactPatterns,
        logger: Logger = NoOpLogger,
    ): Map<String, List<UncoveredRange>> {
        if (workingDir.isBlank() || changedPaths.isEmpty()) return emptyMap()
        val root = Path(workingDir)
        if (!root.exists()) return emptyMap()

        val coverageFiles = mutableListOf<Path>()
        collectCoverageFiles(root, "", patterns, coverageFiles)
        if (coverageFiles.isEmpty()) return emptyMap()

        val merged = mutableMapOf<String, List<UncoveredRange>>()
        for (file in coverageFiles) {
            val content = safeReadText(file, logger) ?: continue
            val parsed = if (file.safeName.endsWith(".xml")) JacocoParser.parse(content) else LcovParser.parse(content)
            parsed.forEach { (path, ranges) ->
                merged[path] = (merged[path] ?: emptyList()) + ranges
            }
        }

        return merged.filterKeys { filePath -> changedPaths.any { filePath.endsWith(it) || it.endsWith(filePath) } }
    }

    private fun collectCoverageFiles(
        dir: Path,
        relativeDir: String,
        patterns: List<String>,
        result: MutableList<Path>,
    ) {
        if (relativeDir.count { it == '/' } > MAX_SCAN_DEPTH) return
        val entries = dir.listFiles() ?: return
        for (entry in entries) {
            val relative = relativeDir + entry.safeName
            when {
                entry.isFile() && patterns.any { GlobMatcher.matches(relative, it) } -> result.add(entry)
                entry.isDirectory() -> collectCoverageFiles(entry, "$relative/", patterns, result)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun safeReadText(
        file: Path,
        logger: Logger
    ): String? =
        try {
            file.readText().also { content ->
                if (content.length > MAX_FILE_SIZE_BYTES) {
                    logger.warn("CI enrichment: coverage file ${file.safeName} exceeds 5 MB — skipping")
                    return null
                }
            }
        } catch (e: Exception) {
            // boundary: file read failure degrades to null
            logger.debug("CI enrichment: failed to read coverage file ${file.safeName}: ${e.message}")
            null
        }
}
