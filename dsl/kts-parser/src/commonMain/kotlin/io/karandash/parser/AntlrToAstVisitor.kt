package io.karandash.parser

import io.karandash.parser.ast.Assignment
import io.karandash.parser.ast.AstNode
import io.karandash.parser.ast.Block
import io.karandash.parser.ast.ComparisonExpr
import io.karandash.parser.ast.ConfigAst
import io.karandash.parser.ast.Expression
import io.karandash.parser.ast.FieldAccessExpr
import io.karandash.parser.ast.FunctionCall
import io.karandash.parser.ast.FunctionCallExpr
import io.karandash.parser.ast.IfStatement
import io.karandash.parser.ast.ListExpr
import io.karandash.parser.ast.LiteralExpr
import io.karandash.parser.ast.LogicalExpr
import io.karandash.parser.ast.NamedArgExpr
import io.karandash.parser.ast.NotExpr
import io.karandash.parser.ast.Statement
import io.karandash.parser.ast.TopLevelBlock
import io.karandash.parser.generated.KarandashDslBaseVisitor
import io.karandash.parser.generated.KarandashDslParser

/**
 * Visitor that transforms ANTLR parse tree into our AST nodes
 */
class AntlrToAstVisitor : KarandashDslBaseVisitor<AstNode>() {
    override fun defaultResult(): AstNode {
        error("Unsupported node type")
    }

    /** Single-pass Kotlin string unescape, so `"\\n"` stays a backslash followed by `n`. */
    private fun unescapeString(str: String): String =
        ESCAPE.replace(str) { match ->
            when (val c = match.value[1]) {
                'n' -> "\n"
                'r' -> "\r"
                't' -> "\t"
                else -> c.toString()
            }
        }

    override fun visitConfig(ctx: KarandashDslParser.ConfigContext): ConfigAst {
        val items = ctx.topLevelItem().map { item ->
            item.topLevelBlock()?.let { block ->
                FunctionCall(block.IDENTIFIER().text, emptyList(), Block(block.statement().map { visitStatement(it) }))
            } ?: item.assignment()?.let { visitAssignment(it) }
                ?: visitIfStatement(checkNotNull(item.ifStatement()) { "top-level item has no known form" })
        }
        val blocks = ctx.topLevelItem().mapNotNull { it.topLevelBlock() }.map { visitTopLevelBlock(it) }
        return ConfigAst(blocks, items)
    }

    override fun visitTopLevelBlock(ctx: KarandashDslParser.TopLevelBlockContext): TopLevelBlock {
        val name = ctx.IDENTIFIER().text
        val statements = ctx.statement().map { visitStatement(it) }
        return TopLevelBlock(name, statements)
    }

    override fun visitStatement(ctx: KarandashDslParser.StatementContext): Statement =
        when {
            ctx.assignment() != null -> visitAssignment(checkNotNull(ctx.assignment()) { "assignment node unexpectedly null" })
            ctx.functionCall() != null -> visitFunctionCall(checkNotNull(ctx.functionCall()) { "functionCall node unexpectedly null" })
            ctx.ifStatement() != null -> visitIfStatement(checkNotNull(ctx.ifStatement()) { "ifStatement node unexpectedly null" })
            else -> error("Unknown statement type")
        }

    override fun visitAssignment(ctx: KarandashDslParser.AssignmentContext): Assignment {
        val name = ctx.IDENTIFIER().text
        val value = visit(ctx.expression()) as Expression
        return Assignment(name, value)
    }

    override fun visitFunctionCall(ctx: KarandashDslParser.FunctionCallContext): FunctionCall {
        val name = ctx.IDENTIFIER().text
        val block = ctx.block()?.let { visitBlock(it) }
        return FunctionCall(name, arguments(ctx.argumentList()), block)
    }

    private fun arguments(ctx: KarandashDslParser.ArgumentListContext?): List<Expression> =
        ctx?.argument()?.map { arg ->
            val value = visit(arg.expression()) as Expression
            arg.IDENTIFIER()?.let { NamedArgExpr(it.text, value) } ?: value
        } ?: emptyList()

    override fun visitIfStatement(ctx: KarandashDslParser.IfStatementContext): IfStatement {
        val condition = visit(ctx.expression()) as Expression
        val thenBlock = visitBlock(checkNotNull(ctx.block(0)) { "then-block unexpectedly null in if-statement" })
        // `else if (...) { }` is an else-block holding the nested if statement.
        val elseBlock = ctx.ifStatement()?.let { Block(listOf(visitIfStatement(it))) }
            ?: ctx.block(1)?.let { visitBlock(it) }
        return IfStatement(condition, thenBlock, elseBlock)
    }

    override fun visitBlock(ctx: KarandashDslParser.BlockContext): Block {
        val statements = ctx.statement().map { visitStatement(it) }
        return Block(statements)
    }

    override fun visitParenExpr(ctx: KarandashDslParser.ParenExprContext): Expression {
        // For parenthesized expressions, just visit the inner expression
        return visit(ctx.expression()) as Expression
    }

    override fun visitLiteralExpr(ctx: KarandashDslParser.LiteralExprContext): Expression {
        val literal = ctx.literal()

        // Handle list literals specially - they return ListExpr not LiteralExpr
        if (literal.listLiteral() != null) {
            return visitListLiteral(checkNotNull(literal.listLiteral()) { "listLiteral unexpectedly null" })
        }

        val value = when {
            literal.STRING() != null -> {
                val text = checkNotNull(literal.STRING()) { "STRING token unexpectedly null" }.text
                // Remove quotes and unescape the string
                unescapeString(text.substring(1, text.length - 1))
            }

            literal.NUMBER() != null -> {
                val text = checkNotNull(literal.NUMBER()) { "NUMBER token unexpectedly null" }.text
                if (text.contains('.')) text.toDouble() else text.toInt()
            }

            literal.BOOLEAN() != null -> {
                checkNotNull(literal.BOOLEAN()) { "BOOLEAN token unexpectedly null" }.text.toBoolean()
            }

            else -> {
                error("Unknown literal type")
            }
        }
        return LiteralExpr(value)
    }

    override fun visitListLiteral(ctx: KarandashDslParser.ListLiteralContext): Expression {
        val elements = ctx.expression().map { visit(it) as Expression }
        return ListExpr(elements)
    }

    override fun visitFieldAccessExpr(ctx: KarandashDslParser.FieldAccessExprContext): FieldAccessExpr {
        val fieldAccess = ctx.fieldAccess()
        val path = fieldAccess.IDENTIFIER().map { it.text }
        return FieldAccessExpr(path)
    }

    override fun visitComparisonExpr(ctx: KarandashDslParser.ComparisonExprContext): ComparisonExpr {
        val left = visit(checkNotNull(ctx.expression(0)) { "left expression unexpectedly null in comparison" }) as Expression
        val right = visit(checkNotNull(ctx.expression(1)) { "right expression unexpectedly null in comparison" }) as Expression
        val op = ctx.comparisonOp().text
        return ComparisonExpr(left, op, right)
    }

    override fun visitAndExpr(ctx: KarandashDslParser.AndExprContext): LogicalExpr = logical(ctx.expression(0), "&&", ctx.expression(1))

    override fun visitOrExpr(ctx: KarandashDslParser.OrExprContext): LogicalExpr = logical(ctx.expression(0), "||", ctx.expression(1))

    private fun logical(
        left: KarandashDslParser.ExpressionContext?,
        op: String,
        right: KarandashDslParser.ExpressionContext?,
    ): LogicalExpr =
        LogicalExpr(
            visit(checkNotNull(left) { "left expression unexpectedly null in '$op'" }) as Expression,
            op,
            visit(checkNotNull(right) { "right expression unexpectedly null in '$op'" }) as Expression,
        )

    override fun visitNotExpr(ctx: KarandashDslParser.NotExprContext): NotExpr = NotExpr(visit(ctx.expression()) as Expression)

    override fun visitFunctionCallExpression(ctx: KarandashDslParser.FunctionCallExpressionContext): FunctionCallExpr {
        val functionCall = ctx.functionCallExpr()
        return FunctionCallExpr(functionCall.IDENTIFIER().text, arguments(functionCall.argumentList()))
    }

    private companion object {
        val ESCAPE = Regex("""\\.""")
    }
}
