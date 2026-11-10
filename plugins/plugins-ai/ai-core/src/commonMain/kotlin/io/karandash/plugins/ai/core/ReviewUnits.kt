package io.karandash.plugins.ai.core

import io.karandash.models.FileChange
import kotlinx.serialization.Serializable

/** A module or directory of an MR's changes, reviewed together: its tests sit with its sources. */
@Serializable
data class ReviewUnit(
    val name: String,
    val changes: List<FileChange>,
)

/** Groups changed files into [ReviewUnit]s and tells test files from production files. */
object ReviewUnits {
    /** The unit of the repository root. */
    const val ROOT: String = "."

    private val TEST_DIRS = setOf("test", "tests", "__tests__", "spec")
    private val MODULE_DIRS = setOf("src", "lib")
    private val TEST_STEM_SUFFIXES = listOf("_test", "Test", "Tests", "Spec", ".test", ".spec")

    /**
     * The unit of [path]: the module root before `src/` or `lib/`, else its directory without test directories, so
     * `src/test` and `src/androidTest` sit with `src/main`, `test/` with `lib/`, and `tests/x` with `x`.
     */
    fun of(path: String): String {
        val dirs = path.split('/').dropLast(1)
        val root = dirs.indexOfFirst { it in MODULE_DIRS }
        val unit = if (root >= 0) dirs.take(root) else dirs.filterNot { it in TEST_DIRS }
        return unit.joinToString("/").ifEmpty { ROOT }
    }

    /**
     * [changes] by unit, the riskiest unit first (highest [DiffChunker.filePriority], then most lines changed). A test
     * whose own unit changed no production file joins the unit of the one production file it is named for
     * (`tests/test_mod.py` with `pkg/mod.py`, `foo_test.cc` with `foo.cc`).
     */
    fun group(changes: List<FileChange>): List<ReviewUnit> {
        val production = changes.filterNot { isTest(it.path) }
        val productionUnits = production.mapTo(mutableSetOf()) { of(it.path) }
        val unitByStem = production
            .groupBy({ stem(it.path) }, { of(it.path) })
            .mapValues { (_, units) -> units.distinct().singleOrNull() }
        return changes
            .groupBy { change ->
                val own = of(change.path)
                if (own in productionUnits || !isTest(change.path)) own else unitByStem[testedStem(change.path)] ?: own
            }.map { (name, files) -> ReviewUnit(name, files.sortedByDescending { DiffChunker.filePriority(it.path) }) }
            .sortedWith(
                compareByDescending<ReviewUnit> { unit -> unit.changes.maxOf { DiffChunker.filePriority(it.path) } }
                    .thenByDescending { unit -> unit.changes.sumOf { it.additions + it.deletions } },
            )
    }

    private fun stem(path: String): String = path.substringAfterLast('/').substringBeforeLast('.')

    private fun testedStem(path: String): String {
        val stem = stem(path).removePrefix("test_")
        return TEST_STEM_SUFFIXES.firstOrNull { stem.endsWith(it) }?.let { stem.removeSuffix(it) } ?: stem
    }

    /** Whether [path] is a test: under a test directory (`src/test`, `src/androidTest`, `tests/`) or named as one. */
    fun isTest(path: String): Boolean {
        val segments = path.split('/')
        val dirs = segments.dropLast(1)
        if (dirs.any { it in TEST_DIRS || it.endsWith("Test") }) return true
        val name = segments.last()
        val stem = name.substringBeforeLast('.')
        return name.startsWith("test_") || TEST_STEM_SUFFIXES.any { stem.endsWith(it) }
    }
}
