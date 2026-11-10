package io.karandash.parser

import io.karandash.parser.generated.KarandashDslLexer
import io.karandash.parser.generated.KarandashDslParser
import org.antlr.v4.kotlinruntime.CharStreams
import org.antlr.v4.kotlinruntime.CommonTokenStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ParserBasicTest {
    @Test
    fun `should parse simple rules block`() {
        val input = """
            rules {
                pattern = "test"
            }
        """.trimIndent()

        val parser = createParser(input)
        val config = parser.config()

        assertNotNull(config, "Config should be parsed")
        assertEquals(0, parser.numberOfSyntaxErrors, "Should have no syntax errors")
        assertTrue(config.topLevelItem().size > 0, "Should have at least one top-level block")
    }

    @Test
    fun `should parse assignment statement`() {
        val input = """
            rules {
                pattern = "^(feat|fix): .+"
                minLength = 10
                enabled = true
            }
        """.trimIndent()

        val parser = createParser(input)
        val config = parser.config()

        assertNotNull(config)
        assertEquals(0, parser.numberOfSyntaxErrors, "Should have no syntax errors")

        val rulesBlock = config.topLevelItem(0)!!.topLevelBlock()!!
        assertEquals("rules", rulesBlock.IDENTIFIER().text)
        assertTrue(rulesBlock.statement().size >= 3, "Should have at least 3 statements")
    }

    @Test
    fun `should parse function call with block`() {
        val input = """
            rules {
                titleFormat {
                    pattern = "test"
                }
            }
        """.trimIndent()

        val parser = createParser(input)
        val config = parser.config()

        assertNotNull(config)
        assertEquals(0, parser.numberOfSyntaxErrors, "Should have no syntax errors")
    }

    @Test
    fun `should parse if statement`() {
        val input = """
            rules {
                if (env.STRICT == "true") {
                    pattern = "strict-pattern"
                }
            }
        """.trimIndent()

        val parser = createParser(input)
        val config = parser.config()

        assertNotNull(config)
        assertEquals(0, parser.numberOfSyntaxErrors, "Should have no syntax errors")
    }

    @Test
    fun `should parse field access expression`() {
        val input = """
            rules {
                value = mr.title
            }
        """.trimIndent()

        val parser = createParser(input)
        val config = parser.config()

        assertNotNull(config)
        assertEquals(0, parser.numberOfSyntaxErrors, "Should have no syntax errors")
    }

    @Test
    fun `should parse list literal`() {
        val input = """
            rules {
                values = listOf("one", "two", "three")
            }
        """.trimIndent()

        val parser = createParser(input)
        val config = parser.config()

        assertNotNull(config)
        assertEquals(0, parser.numberOfSyntaxErrors, "Should have no syntax errors")
    }

    @Test
    fun `should parse multiple top-level blocks`() {
        val input = """
            rules {
                pattern = "test"
            }

            auth {
                token = "secret"
            }

            reporters {
                console = true
            }
        """.trimIndent()

        val parser = createParser(input)
        val config = parser.config()

        assertNotNull(config)
        assertEquals(0, parser.numberOfSyntaxErrors, "Should have no syntax errors")
        assertEquals(3, config.topLevelItem().size, "Should have 3 top-level blocks")
    }

    @Test
    fun `should handle comments`() {
        val input = """
            // This is a line comment
            rules {
                // Another comment
                pattern = "test"
                /* Block comment */
                enabled = true
            }
        """.trimIndent()

        val parser = createParser(input)
        val config = parser.config()

        assertNotNull(config)
        assertEquals(0, parser.numberOfSyntaxErrors, "Should have no syntax errors")
    }

    private fun createParser(input: String): KarandashDslParser {
        val charStream = CharStreams.fromString(input)
        val lexer = KarandashDslLexer(charStream)
        val tokens = CommonTokenStream(lexer)
        return KarandashDslParser(tokens)
    }
}
