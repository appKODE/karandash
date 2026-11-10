package io.karandash.cli.commands.format

import com.github.ajalt.clikt.testing.test
import io.karandash.cli.TestFileHelpers
import io.karandash.models.support.KarandashJson
import kotlinx.io.files.Path
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class FormatCommandTest {
    private lateinit var tempDir: Path

    @BeforeTest
    fun setup() {
        tempDir = TestFileHelpers.createTempDir("karandash-format-test")
    }

    @AfterTest
    fun cleanup() {
        TestFileHelpers.deleteTempDir(tempDir)
    }

    private fun file(
        name: String,
        text: String,
    ): String {
        val path = Path(tempDir, name)
        TestFileHelpers.writeFile(path, text)
        return path.toString()
    }

    @Test
    fun `schema prints the template data of the example report`() {
        val result = FormatSchemaCommand().test(emptyList())

        assertEquals(0, result.statusCode)
        val data = KarandashJson.Default.parseToJsonElement(result.output).jsonObject
        assertEquals(3, data.getValue("findings").jsonArray.size)
        assertContains(data.keys, "headline")
        assertContains(data.keys, "labels")
    }

    @Test
    fun `render fills a template and its partials with the escape chosen`() {
        val template = file("t.mustache", "{{#findings}}{{>row}}{{/findings}}{{#md}}{{{headline}}}{{/md}}")
        val row = file("row.mustache", "<{{rule}}>\n")

        val result = FormatRenderCommand().test(listOf("--template", template, "--partial", "row=$row", "--escape", "html"))

        assertEquals(0, result.statusCode, result.stderr)
        assertContains(result.output, "<title-length>\n")
        assertContains(result.output, "Failed")
    }

    @Test
    fun `render accepts markdown as the md escape like the template format`() {
        val template = file("m.mustache", "{{x}}")
        val result = FormatRenderCommand().test(listOf("--template", template, "--escape", "Markdown"))
        assertEquals(0, result.statusCode, result.stderr)
    }

    @Test
    fun `render reports a broken template and a missing file`() {
        val broken = FormatRenderCommand().test(listOf("--template", file("b.mustache", "{{#findings}}")))
        assertEquals(2, broken.statusCode)
        assertContains(broken.stderr, "Template error")

        val missing = FormatRenderCommand().test(listOf("--template", Path(tempDir, "none.mustache").toString()))
        assertEquals(2, missing.statusCode)
        assertContains(missing.stderr, "File not found")
    }
}
