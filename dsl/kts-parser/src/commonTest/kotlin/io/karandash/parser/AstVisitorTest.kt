package io.karandash.parser

import io.karandash.parser.ast.Assignment
import io.karandash.parser.ast.ComparisonExpr
import io.karandash.parser.ast.FieldAccessExpr
import io.karandash.parser.ast.FunctionCall
import io.karandash.parser.ast.IfStatement
import io.karandash.parser.ast.ListExpr
import io.karandash.parser.ast.LiteralExpr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AstVisitorTest {
    private val parser = KarandashParser()

    @Test
    fun `should transform parse tree to AST`() {
        val input = """
            rules {
                pattern = "test"
            }
        """.trimIndent()

        val ast = parser.parseToAst(input)

        assertNotNull(ast)
        assertEquals(1, ast.blocks.size)

        val rulesBlock = ast.blocks[0]
        assertEquals("rules", rulesBlock.name)
        assertEquals(1, rulesBlock.statements.size)

        val assignment = rulesBlock.statements[0] as Assignment
        assertEquals("pattern", assignment.name)
        val literalExpr = assignment.value as LiteralExpr
        assertEquals("test", literalExpr.value)
    }

    @Test
    fun `should handle function calls with blocks`() {
        val input = """
            rules {
                titleFormat {
                    pattern = "^(feat|fix): .+"
                    minLength = 10
                }
            }
        """.trimIndent()

        val ast = parser.parseToAst(input)

        val rulesBlock = ast.blocks[0]
        val functionCall = rulesBlock.statements[0] as FunctionCall

        assertEquals("titleFormat", functionCall.name)
        val block = functionCall.block
        assertNotNull(block)
        assertEquals(2, block.statements.size)
    }

    @Test
    fun `should handle if statements`() {
        val input = """
            rules {
                if (env.STRICT == "true") {
                    pattern = "strict"
                }
            }
        """.trimIndent()

        val ast = parser.parseToAst(input)

        val rulesBlock = ast.blocks[0]
        val ifStmt = rulesBlock.statements[0] as IfStatement

        assertTrue(ifStmt.condition is ComparisonExpr)
        assertEquals(1, ifStmt.thenBlock.statements.size)
    }

    @Test
    fun `should handle field access expressions`() {
        val input = """
            rules {
                value = mr.title
            }
        """.trimIndent()

        val ast = parser.parseToAst(input)

        val assignment = ast.blocks[0].statements[0] as Assignment
        val fieldAccess = assignment.value as FieldAccessExpr

        assertEquals(listOf("mr", "title"), fieldAccess.path)
    }

    @Test
    fun `should handle list expressions`() {
        val input = """
            rules {
                values = listOf("one", "two", "three")
            }
        """.trimIndent()

        val ast = parser.parseToAst(input)

        val assignment = ast.blocks[0].statements[0] as Assignment

        // listOf() is parsed as a ListExpr (list literal), not a function call
        when (val value = assignment.value) {
            is ListExpr -> {
                assertEquals(3, value.elements.size)
                // Check that all elements are string literals
                value.elements.forEach { element ->
                    assertTrue(element is LiteralExpr)
                }
            }

            else -> {
                error("Expected ListExpr for listOf, got ${value::class.simpleName}")
            }
        }
    }

    @Test
    fun `should handle multiple top-level blocks`() {
        val input = """
            rules {
                pattern = "test"
            }

            auth {
                token = "secret"
            }
        """.trimIndent()

        val ast = parser.parseToAst(input)

        assertEquals(2, ast.blocks.size)
        assertEquals("rules", ast.blocks[0].name)
        assertEquals("auth", ast.blocks[1].name)
    }
}
