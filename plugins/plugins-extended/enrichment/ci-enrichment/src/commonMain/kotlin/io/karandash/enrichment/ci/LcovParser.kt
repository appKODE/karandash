package io.karandash.enrichment.ci

/**
 * Parses `lcov.info` format into a map of file path → uncovered line ranges.
 *
 * Only lines with a hit count of zero are reported. Consecutive uncovered lines
 * are merged into a single [UncoveredRange].
 */
internal object LcovParser {
    private const val SOURCE_FILE_PREFIX = "SF:"
    private const val DATA_LINE_PREFIX = "DA:"
    private const val END_OF_RECORD = "end_of_record"

    fun parse(lcovContent: String): Map<String, List<UncoveredRange>> {
        val result = mutableMapOf<String, List<UncoveredRange>>()
        var currentFile: String? = null
        val uncoveredLines = mutableListOf<Int>()

        for (rawLine in lcovContent.lines()) {
            val line = rawLine.trim()
            when {
                line.startsWith(SOURCE_FILE_PREFIX) -> {
                    currentFile = line.removePrefix(SOURCE_FILE_PREFIX).trim()
                    uncoveredLines.clear()
                }

                line.startsWith(DATA_LINE_PREFIX) -> {
                    val parts = line.removePrefix(DATA_LINE_PREFIX).split(",")
                    if (parts.size >= 2) {
                        val lineNum = parts[0].trim().toIntOrNull() ?: continue
                        val hits = parts[1].trim().toLongOrNull() ?: continue
                        if (hits == 0L) uncoveredLines.add(lineNum)
                    }
                }

                line == END_OF_RECORD -> {
                    val file = currentFile ?: continue
                    if (uncoveredLines.isNotEmpty()) {
                        result[file] = mergeToRanges(uncoveredLines.sorted())
                    }
                    currentFile = null
                    uncoveredLines.clear()
                }
            }
        }
        return result
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
