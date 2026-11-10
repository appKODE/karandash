package io.karandash.cli.compat

import io.karandash.api.errors.KarandashError
import io.karandash.testfixtures.TestFiles
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every code in the `docs/reference/error-codes.md` catalog is raised somewhere in production
 * code (`KarandashError.X` in a production source set (`commonMain`, `jvmMain`, ...) outside the catalog's own declaration), so the docs never list
 * a code the CLI cannot print. Sources are read from the repo checkout, no subprocess.
 */
class ErrorCodeRaiseSitesTest {
    @Test
    fun `every documented error code has a raise site`() {
        val root = File(checkNotNull(TestFiles.findRepoRoot()) { "repo root not found" }.toString())
        val documented = CATALOG_ROW.findAll(File(root, "docs/reference/error-codes.md").readText()).map { it.groupValues[1] }.toList()
        assertTrue(documented.size >= MIN_CODES, "expected at least $MIN_CODES catalog rows, got $documented")

        val raised = root
            .walkTopDown()
            .onEnter { it.name !in SKIPPED_DIRS }
            .filter { it.isFile && it.extension == "kt" && isProductionSource(it.relativeTo(root).invariantSeparatorsPath) }
            .flatMap { RAISE.findAll(it.readText()).map { m -> m.groupValues[1] } }
            .toSet()

        val unraised = documented.filter { code -> KarandashError.fromCode(code)?.name !in raised }
        assertEquals(emptyList(), unraised, "documented codes without a raise site")
    }

    private fun isProductionSource(path: String): Boolean =
        "/src/" in "/$path" && Regex("""/src/[A-Za-z]*Main/""").containsMatchIn("/$path") && !path.startsWith(CATALOG_DIR)

    private companion object {
        const val MIN_CODES = 30
        const val CATALOG_DIR = "core/api/src/commonMain/kotlin/io/karandash/api/errors/"
        val SKIPPED_DIRS = setOf("build", ".git", ".gradle", ".kotlin", "node_modules")
        val CATALOG_ROW = Regex("""^\| <a id="e\d{4}"></a>`(E\d{4})` \|""", RegexOption.MULTILINE)
        val RAISE = Regex("""KarandashError\.([A-Z_]+)""")
    }
}
