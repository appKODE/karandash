package io.karandash.cli.docs

import io.karandash.testfixtures.TestFiles
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every repository path `docs/development/feature-matrix.md` cites (a behavior test, an `e2e.d/` script) and every
 * relative doc link in it exists, so a renamed or deleted test cannot stay cited as coverage.
 */
class FeatureMatrixPathsTest {
    private val root = File(checkNotNull(TestFiles.findRepoRoot()) { "repo root not found" }.toString())
    private val matrix = File(root, MATRIX)

    @Test
    fun `every cited repository path exists`() {
        val cited = CITED_PATH.findAll(matrix.readText()).map { it.groupValues[1] }.toSet()
        assertTrue(cited.size >= MIN_CITED, "expected at least $MIN_CITED cited paths, got ${cited.size}")
        val missing = cited.filterNot { path ->
            val base = if (path.startsWith(E2E_PREFIX)) File(root, E2E_DIR) else root
            File(base, path).exists()
        }
        assertEquals(emptyList(), missing.sorted(), "cited in $MATRIX but missing")
    }

    @Test
    fun `every relative doc link resolves`() {
        val links = DOC_LINK.findAll(matrix.readText()).map { it.groupValues[1] }.toSet()
        val missing = links.filterNot { File(matrix.parentFile, it).exists() }
        assertEquals(emptyList(), missing.sorted(), "linked from $MATRIX but missing")
    }

    private companion object {
        const val MATRIX = "docs/development/feature-matrix.md"
        const val E2E_PREFIX = "e2e.d/"
        const val E2E_DIR = "tools/integration-tests"
        const val MIN_CITED = 500
        val CITED_PATH = Regex(
            """`((?:cli|cli-ai|cli-app|core|plugins|dsl|tools|test-fixtures|projects|docs|build-logic|e2e\.d)/[\w./-]+\.\w+)`""",
        )
        val DOC_LINK = Regex("""]\((\.\./[^)#\s]+)""")
    }
}
