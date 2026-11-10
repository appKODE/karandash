package io.karandash.detekt

import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The rule checks each `Regex(...)` call's pattern argument, so a new `Regex(userInput)` is caught in any file.
 */
class DynamicRegexTest {
    @Test
    fun `literal and escaped patterns pass anywhere`() {
        val code =
            """
            val a = Regex("^[a-z]+$")
            val b = Regex(${"\"\"\""}\s+${"\"\"\""}, RegexOption.IGNORE_CASE)
            val c = Regex("\\b${'$'}{Regex.escape(project)}-\\d+\\b")
            val d = Regex(Regex.escape(name))
            val e = "\\s+".toRegex()
            val f = Regex.fromLiteral(name)
            val g = Regex("a" + "b")
            val h = Regex(${"\"\"\""}^x\s*${'$'}{'${'$'}'}${"\"\"\""})
            """.trimIndent()
        assertEquals(0, DynamicRegex(TestConfig()).lint(code).size)
    }

    @Test
    fun `a pattern built from a variable is reported`() {
        val code =
            """
            val a = Regex(pattern)
            val b = Regex("^${'$'}prefix.*")
            val c = Regex("x${'$'}{config.value}")
            val d = pattern.toRegex()
            val e = Regex(pattern = userPattern, option = RegexOption.IGNORE_CASE)
            val f = Regex("a" + suffix)
            """.trimIndent()
        assertEquals(6, DynamicRegex(TestConfig()).lint(code).size)
    }
}
