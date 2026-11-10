package io.karandash.enrichment.ci

/**
 * Parses JaCoCo XML coverage reports into a map of file path → uncovered line ranges.
 *
 * Uses a simple line-by-line parser that handles the structured but predictable JaCoCo
 * storage format without requiring a full XML DOM library (KMP-compatible).
 * Only lines with missed instructions (`mi` > 0) are reported as uncovered.
 */
internal object JacocoParser {
    private val PACKAGE_REGEX = Regex("""<package\s+name="([^"]+)"""")
    private val SOURCEFILE_REGEX = Regex("""<sourcefile\s+name="([^"]+)"""")
    private val LINE_REGEX = Regex("""<line\s+nr="(\d+)"\s+mi="(\d+)"""")

    fun parse(xmlContent: String): Map<String, List<UncoveredRange>> {
        val result = mutableMapOf<String, List<UncoveredRange>>()
        var currentPackage = ""
        var currentFile: String? = null
        val uncoveredLines = mutableListOf<Int>()

        for (rawLine in xmlContent.lines()) {
            val line = rawLine.trim()
            PACKAGE_REGEX.find(line)?.let { currentPackage = it.groupValues[1] }
            SOURCEFILE_REGEX.find(line)?.let { match ->
                flushFile(currentFile, uncoveredLines, result)
                currentFile = "$currentPackage/${match.groupValues[1]}"
                uncoveredLines.clear()
            }
            LINE_REGEX.find(line)?.let { match ->
                val lineNum = match.groupValues[1].toIntOrNull() ?: return@let
                val missed = match.groupValues[2].toIntOrNull() ?: return@let
                if (missed > 0) uncoveredLines.add(lineNum)
            }
            if (line.contains("</sourcefile>")) {
                flushFile(currentFile, uncoveredLines, result)
                currentFile = null
                uncoveredLines.clear()
            }
        }
        return result
    }

    private fun flushFile(
        filePath: String?,
        lines: List<Int>,
        result: MutableMap<String, List<UncoveredRange>>,
    ) {
        if (filePath == null || lines.isEmpty()) return
        result[filePath] = mergeToRanges(lines.sorted())
    }

    private fun mergeToRanges(sorted: List<Int>): List<UncoveredRange> {
        if (sorted.isEmpty()) return emptyList()
        val ranges = mutableListOf<UncoveredRange>()
        var start = sorted[0]
        var end = sorted[0]
        for (i in 1 until sorted.size) {
            if (sorted[i] == end + 1) {
                end = sorted[i]
            } else {
                ranges.add(UncoveredRange(startLine = start, endLine = end))
                start = sorted[i]
                end = sorted[i]
            }
        }
        ranges.add(UncoveredRange(startLine = start, endLine = end))
        return ranges
    }
}
