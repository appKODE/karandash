package io.karandash.plugins.formats

import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestFinding
import io.karandash.testfixtures.createTestReport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The JUnit XML is well formed and its counters agree with its test cases for every finding kind. */
class JunitSchemaTest {
    @Test
    fun `output is well formed and the counters match the test cases`() =
        runTest {
            val findings =
                Severity.entries.map { createTestFinding(ruleId = "rule", severity = it, message = "m \"q\" <t> & ${it.name}") } +
                    createTestFinding(ruleId = "other", message = "f", file = "src/A.kt", line = 7) +
                    createTestFinding(ruleId = "other", message = "c").copy(location = Location.CommitRef("abc"))

            val xml = JunitFormatModule().format(createTestReport(findings = findings), JsonObject(emptyMap())).body

            assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"), xml)
            assertNull(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]").find(xml), "XML 1.0 forbidden character in output")
            assertBalanced(xml)
            val suites = Regex("<testsuite ").findAll(xml).count()
            val cases = Regex("<testcase ").findAll(xml).count()
            val failures = Regex("<failure ").findAll(xml).count()
            assertEquals(findings.size, cases)
            assertTrue(suites >= 1)
            val root = xml.substringAfter("<testsuites ").substringBefore(">")
            assertEquals(cases, attribute(root, "tests"))
            assertEquals(failures, attribute(root, "failures"))
            val suiteHeads = Regex("<testsuite ([^>]*)>").findAll(xml).map { it.groupValues[1] }.toList()
            assertEquals(cases, suiteHeads.sumOf { attribute(it, "tests") })
            assertEquals(failures, suiteHeads.sumOf { attribute(it, "failures") })
        }

    private fun attribute(
        tag: String,
        name: String,
    ): Int =
        Regex("""\b$name="(\d+)"""")
            .find(tag)
            ?.groupValues
            ?.get(1)
            ?.toInt() ?: error("no $name in $tag")

    /** Every tag is closed in order, and text and attribute values hold no raw `<` or bare `&`. */
    private fun assertBalanced(xml: String) {
        val stack = ArrayDeque<String>()
        Regex("<(/?)([A-Za-z][\\w-]*)([^>]*?)(/?)>").findAll(xml).forEach { tag ->
            val (closing, name, _, selfClosing) = tag.destructured
            when {
                selfClosing == "/" -> Unit
                closing == "/" -> assertEquals(name, stack.removeLastOrNull(), "unbalanced </$name>")
                else -> stack.addLast(name)
            }
        }
        assertTrue(stack.isEmpty(), "unclosed tags: $stack")
        val text = xml.replace(Regex("<[^>]*>"), "")
        assertTrue('<' !in text && '>' !in text, "raw angle bracket in text")
        assertNull(Regex("&(?!(amp|lt|gt|quot|apos|#\\d+);)").find(xml), "bare ampersand in output")
    }
}
