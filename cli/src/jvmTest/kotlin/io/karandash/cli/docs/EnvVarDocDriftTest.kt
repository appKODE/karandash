package io.karandash.cli.docs

import io.karandash.interpolation.KarandashEnvSecurity
import io.karandash.testfixtures.TestFiles
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `docs/reference/environment-variables.md` lists exactly the environment variables the production code reads.
 *
 * The read set comes from production sources (`src/<x>Main/` under the product modules):
 * - every `UPPER_SNAKE` string literal in a file that reads the environment (`getEnv(`, `env(`, `getenv(`, `envReader`);
 * - every all-caps literal passed straight to an env read (`env("CI")`, `getEnv("TERM")`);
 * - every constant of an `*EnvVars.kt` object (`AiEnvVars`, `CliEnvVars`) and every `const val` whose name says it
 *   names an env var (`ENV`, `ENV_VAR`, `TLS_INSECURE_HOSTS_ENV`).
 *
 * A documented name must be in that set, be a `KarandashEnvSecurity.DENYLIST` entry (documented as blocked), or have
 * its row marked `*(external)*` (read by another tool, such as a generated plugin's Gradle build).
 */
class EnvVarDocDriftTest {
    private val root = File(checkNotNull(TestFiles.findRepoRoot()) { "repo root not found" }.toString())

    @Test
    fun `every env var the code reads is documented`() {
        val undocumented = readSet() - documented().keys
        assertEquals(emptySet(), undocumented, "read in production code but missing from $DOC")
    }

    @Test
    fun `every documented env var is read by the code`() {
        val docs = documented()
        assertTrue(docs.size >= MIN_DOCUMENTED, "expected at least $MIN_DOCUMENTED documented names, got ${docs.keys}")
        val unread = docs.filterValues { external -> !external }.keys - readSet() - KarandashEnvSecurity.DENYLIST
        assertEquals(emptySet(), unread, "documented in $DOC but never read (mark a row *(external)* if another tool reads it)")
    }

    /** Documented names mapped to whether their row is marked external. */
    private fun documented(): Map<String, Boolean> =
        File(root, DOC)
            .readLines()
            .filter { it.startsWith("| `") }
            .flatMap { row ->
                val firstCell = row.removePrefix("|").substringBefore("|")
                NAME_IN_CELL.findAll(firstCell).map { it.groupValues[1] to (EXTERNAL in row) }
            }.toMap()

    private fun readSet(): Set<String> =
        productionSources().flatMapTo(mutableSetOf()) { file ->
            val code = file.readLines().filterNot { line -> COMMENT_STARTS.any { line.trimStart().startsWith(it) } }
            val text = code.joinToString("\n")
            val names = mutableSetOf<String>()
            DIRECT_READ.findAll(text).mapTo(names) { it.groupValues[1] }
            if (ENV_READ.containsMatchIn(text)) SNAKE_LITERAL.findAll(text).mapTo(names) { it.groupValues[1] }
            val constants = CONST_LITERAL.findAll(text)
            val envConstants = if (file.name.endsWith("EnvVars.kt")) constants else constants.filter { "ENV" in it.groupValues[1] }
            envConstants.mapTo(names) { it.groupValues[2] }
            names
        }

    private fun productionSources(): Sequence<File> =
        PRODUCT_DIRS.asSequence().map { File(root, it) }.flatMap { dir ->
            dir
                .walkTopDown()
                .onEnter { it.name !in SKIPPED_DIRS }
                .filter { it.isFile && it.extension == "kt" && PRODUCTION_SET.containsMatchIn(it.invariantSeparatorsPath) }
        }

    private companion object {
        const val DOC = "docs/reference/environment-variables.md"
        const val EXTERNAL = "*(external)*"
        const val MIN_DOCUMENTED = 100
        val PRODUCT_DIRS = listOf("cli", "cli-ai", "cli-app", "core", "plugins", "dsl")
        val SKIPPED_DIRS = setOf("build", ".gradle", ".kotlin", "node_modules")
        val PRODUCTION_SET = Regex("""/src/[A-Za-z]*Main/""")
        val NAME_IN_CELL = Regex("""`([A-Z][A-Z0-9_]*)`""")
        val ENV_READ = Regex("""getEnv\(|[eE]nv\(|getenv\(|envReader""")
        val DIRECT_READ = Regex("""[eE]nv\w*\(\s*"([A-Z][A-Z0-9_]*)"""")
        val SNAKE_LITERAL = Regex(""""([A-Z][A-Z0-9]*_[A-Z0-9_]+)"""")
        val CONST_LITERAL = Regex("""const val (\w+) = "([A-Z][A-Z0-9_]*)"""")
        val COMMENT_STARTS = listOf("*", "//", "/*")
    }
}
