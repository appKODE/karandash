package io.karandash.plugins.ai.core.context

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConventionDetectorTest {
    private fun readerOf(files: Map<String, String>): ConventionDetector.FileReader = ConventionDetector.FileReader { path -> files[path] }

    @Test
    fun `detects known editorconfig`() =
        runTest {
            val reader = readerOf(mapOf(".editorconfig" to "indent_style = space\nindent_size = 4\n"))
            val result = ConventionDetector.detect(workingDir = "/tmp/repo", reader = reader)
            assertEquals(1, result.size)
            assertEquals(ConventionExtractors.TYPE_CODE_STYLE, result.single().type)
        }

    @Test
    fun `custom file ranks above built-ins and uses custom type`() =
        runTest {
            val reader = readerOf(
                mapOf(
                    "docs/adr.md" to "# ADR",
                    ".editorconfig" to "indent_style = tab",
                ),
            )
            val result = ConventionDetector.detect(
                workingDir = "/tmp/repo",
                customFiles = listOf("docs/adr.md"),
                reader = reader,
            )
            assertEquals("docs/adr.md", result.first().path)
            assertEquals(ConventionExtractors.TYPE_CUSTOM, result.first().type)
            assertTrue(result.first().priority >= result.last().priority)
        }

    @Test
    fun `traversal in custom path is rejected`() =
        runTest {
            val reader = readerOf(mapOf("../../etc/passwd" to "root:x:0:0"))
            val result = ConventionDetector.detect(
                workingDir = "/tmp/repo",
                customFiles = listOf("../../etc/passwd"),
                reader = reader,
            )
            assertTrue(result.isEmpty())
        }

    @Test
    fun `dedupes by type - first wins`() =
        runTest {
            val reader = readerOf(
                mapOf(
                    "detekt.yml" to "LongMethod:\n  threshold: 120",
                    ".detekt.yml" to "LargeClass:\n  threshold: 500",
                ),
            )
            val result = ConventionDetector.detect(workingDir = "/tmp/repo", reader = reader)
            // Both are TYPE_LINTER - only the first (detekt.yml) is kept.
            assertEquals(1, result.count { it.type == ConventionExtractors.TYPE_LINTER })
            assertEquals("detekt.yml", result.single { it.type == ConventionExtractors.TYPE_LINTER }.path)
        }

    @Test
    fun `budget stops the scan`() =
        runTest {
            val big = "x".repeat(2_000)
            val reader = readerOf(
                mapOf(
                    ".editorconfig" to "indent_style=$big",
                    "CONTRIBUTING.md" to "# review\n$big",
                    "CODEOWNERS" to "*.kt @team",
                ),
            )
            val result = ConventionDetector.detect(
                workingDir = "/tmp/repo",
                changedPaths = listOf("src/Foo.kt"),
                maxChars = 500,
                reader = reader,
            )
            assertTrue(result.sumOf { it.content.length } <= 500)
        }

    @Test
    fun `conventions are read whole without a cap`() =
        runTest {
            val guide = "# review\n" + (1..400).joinToString("\n") { "rule $it keep the style" }
            val reader = readerOf(mapOf("CONTRIBUTING.md" to guide))

            val result = ConventionDetector.detect(workingDir = "/tmp/repo", reader = reader)

            assertTrue(
                result
                    .single()
                    .content
                    .trimEnd()
                    .endsWith("rule 400 keep the style"),
                "conventions are T0 and never cut"
            )
        }

    @Test
    fun `a user cap keeps whole files and never cuts mid-line`() =
        runTest {
            val reader = readerOf(
                mapOf(
                    ".karandash/review-guidelines.md" to "# review\n" + "long guideline line\n".repeat(50),
                    ".editorconfig" to "indent_style = space",
                ),
            )

            val result = ConventionDetector.detect(workingDir = "/tmp/repo", maxChars = 100, reader = reader)

            assertEquals(listOf(".editorconfig"), result.map { it.path }, "a file over the cap is skipped, not cut")
            assertEquals("indent_style=space", result.single().content)
        }

    @Test
    fun `unreadable file yields empty result`() =
        runTest {
            val result = ConventionDetector.detect(
                workingDir = "/tmp/repo",
                reader = ConventionDetector.FileReader { null },
            )
            assertTrue(result.isEmpty())
        }

    @Test
    fun `empty content skipped`() =
        runTest {
            val reader = readerOf(mapOf(".editorconfig" to ""))
            val result = ConventionDetector.detect(workingDir = "/tmp/repo", reader = reader)
            assertTrue(result.isEmpty())
        }
}
