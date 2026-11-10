package io.karandash.detekt

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** a file excluded from `ForbiddenWithTimeout` must still contain a `withTimeout` call, or the exclude is stale. */
class DetektConfigExcludesTest {
    private val repoRoot = File("../..").canonicalFile

    private fun forbiddenWithTimeoutExcludes(): List<String> =
        File(repoRoot, "detekt.yml")
            .readLines()
            .dropWhile { it.trim() != "ForbiddenWithTimeout:" }
            .drop(1)
            .takeWhile { it.startsWith("    ") }
            .map { it.trim() }
            .filter { it.startsWith("- '") }
            .map { it.removePrefix("- '").removeSuffix("'") }
            .filterNot { "/src/" in it && ("Test" in it || "/test/" in it) }

    @Test
    fun `every ForbiddenWithTimeout file exclude names a file that calls withTimeout`() {
        val stale = forbiddenWithTimeoutExcludes().filterNot { exclude ->
            val file = File(repoRoot, exclude.removePrefix("**/"))
            file.isFile && "withTimeout" in file.readText()
        }
        assertTrue(stale.isEmpty(), "stale ForbiddenWithTimeout excludes: $stale")
    }
}
