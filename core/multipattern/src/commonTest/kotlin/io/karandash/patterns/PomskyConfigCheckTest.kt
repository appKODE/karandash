package io.karandash.patterns

import io.karandash.models.support.KarandashJson
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PomskyConfigCheckTest {
    private fun config(json: String): JsonElement = KarandashJson.Default.parseToJsonElement(json)

    @Test
    fun `clean pomsky patterns and other values give no issues`() {
        val issues = PomskyConfigCheck.check(
            config("""{"rules":{"titleFormat":{"pattern":"pomsky:('feat' | 'fix') ': ' .+"},"x":{"pattern":"^a+$"}}}"""),
        )

        assertEquals(emptyList(), issues)
    }

    @Test
    fun `a compile error marks the span with a caret`() {
        val issues = PomskyConfigCheck.check(config("""{"rules":{"titleFormat":{"pattern":"pomsky:'a' unknown_ref"}}}"""))

        val issue = issues.single()
        assertTrue(issue.error)
        assertEquals("rules.titleFormat.pattern", issue.path)
        assertEquals(
            "rules.titleFormat.pattern: pomsky pattern does not compile: Variable `unknown_ref` doesn't exist\n" +
                "    'a' unknown_ref\n" +
                "        ^^^^^^^^^^^",
            issue.message,
        )
        assertTrue(checkNotNull(issue.hint).isNotBlank())
    }

    @Test
    fun `an error at the end gets one caret`() {
        val issue = PomskyConfigCheck.check(config("""{"p":"pomsky:('feat' | 'fix'"}""")).single()

        assertTrue(issue.message.endsWith("\n    ('feat' | 'fix'\n                   ^"), issue.message)
    }

    @Test
    fun `lint diagnostics are warnings with the help as hint`() {
        val issues = PomskyConfigCheck.check(config("""{"rules":{"b":{"patterns":["^x$","pomsky:('a'+)+"]}}}"""))

        assertTrue(issues.isNotEmpty())
        assertTrue(issues.none { it.error })
        assertTrue(issues.all { it.path == "rules.b.patterns[1]" }, issues.toString())
        val redos = issues.first { "backtracking" in it.message }
        assertTrue(checkNotNull(redos.hint).startsWith("Rewrite to avoid nesting"), redos.hint)
    }

    @Test
    fun `a high complexity score is a warning`() {
        val issues = PomskyConfigCheck.check(config("""{"p":"pomsky:(.* .*)* 'x'"}"""))

        val complex = issues.single { "complex" in it.message }
        assertFalse(complex.error)
        assertTrue(complex.message.startsWith("p: pomsky pattern is complex (score 10)"), complex.message)
        assertTrue(checkNotNull(complex.hint).isNotBlank())
    }
}
