package io.karandash.cli.compat

import io.karandash.cli.TestFileHelpers
import io.karandash.platform.PlatformProcess
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No two modules declare the same top-level class, object or interface in the same package. Both would land on the
 * CLI classpath (the fat JAR keeps one of them, the native link fails), as `rdjson-format` and `rdjsonl-format` did
 * with their private `Rd*` DTOs. Private declarations count: they still compile to a class of that name.
 * Scans the `commonMain` sources of every tracked module outside `tools/` and `projects/`.
 */
class DuplicateClassNamesTest {
    @Test
    fun `no fully qualified top-level class is declared in two modules`() {
        val modulesByClass = mutableMapOf<String, MutableSet<String>>()
        sources().forEach { file ->
            val module = file.substringBefore(COMMON_MAIN)
            val text = TestFileHelpers.readFile(Path(file))
            val pkg = PACKAGE
                .find(text)
                ?.groupValues
                ?.get(1)
                .orEmpty()
            TOP_LEVEL_CLASS.findAll(text).forEach { match ->
                modulesByClass.getOrPut("$pkg.${match.groupValues[1]}") { mutableSetOf() }.add(module)
            }
        }
        val clashes = modulesByClass.filterValues { it.size > 1 }.map { (name, modules) -> "$name: ${modules.sorted()}" }

        assertTrue(modulesByClass.size >= MIN_CLASSES, "expected at least $MIN_CLASSES classes, got ${modulesByClass.size}")
        assertEquals(emptyList(), clashes.sorted(), clashes.joinToString("\n"))
    }

    private fun sources(): List<String> {
        val result = PlatformProcess.execute(listOf("git", "ls-files", "*.kt"))
        check(result.isSuccess) { "git ls-files failed: ${(result.error + result.output).lines().take(3)}" }
        return result.output
            .lines()
            .filter { COMMON_MAIN in it && EXCLUDED_ROOTS.none { root -> it.startsWith(root) } }
    }

    private companion object {
        const val COMMON_MAIN = "/src/commonMain/"
        const val MIN_CLASSES = 500
        val EXCLUDED_ROOTS = listOf("tools/", "projects/")
        val PACKAGE = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)

        /** A declaration at column 0 is top-level; modifiers before the keyword are skipped. */
        val TOP_LEVEL_CLASS =
            Regex("""^(?:@\S+\s+)*(?:[a-z]+\s+)*?(?:class|object|interface)\s+(\w+)""", RegexOption.MULTILINE)
    }
}
