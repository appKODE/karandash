package io.karandash.parser.ast

sealed class AstNode

/**
 * @param blocks Top-level `name { ... }` blocks only.
 * @param items Every top-level item in file order: blocks (as a [FunctionCall] with a [Block]),
 *   assignments such as `extends = "..."`, and `if` statements.
 *
 * Not @Serializable: AST node, the tree holds untyped [LiteralExpr] values.
 */
data class ConfigAst(
    val blocks: List<TopLevelBlock>,
    val items: List<Statement> = blocks.map { FunctionCall(it.name, emptyList(), Block(it.statements)) },
) : AstNode()

/** Not @Serializable: AST node, the tree holds untyped [LiteralExpr] values. */
data class TopLevelBlock(
    val name: String,
    val statements: List<Statement>
) : AstNode()

sealed class Statement : AstNode()

/** Not @Serializable: AST node, the tree holds untyped [LiteralExpr] values. */
data class Assignment(
    val name: String,
    val value: Expression
) : Statement()

/** Not @Serializable: AST node, the tree holds untyped [LiteralExpr] values. */
data class FunctionCall(
    val name: String,
    val args: List<Expression>,
    val block: Block?
) : Statement()

/** Not @Serializable: AST node, the tree holds untyped [LiteralExpr] values. */
data class IfStatement(
    val condition: Expression,
    val thenBlock: Block,
    val elseBlock: Block?
) : Statement()

/** Not @Serializable: AST node, the tree holds untyped [LiteralExpr] values. */
data class Block(
    val statements: List<Statement>
) : AstNode()

sealed class Expression : AstNode()

/** Not @Serializable: AST node, the tree holds untyped [LiteralExpr] values. */
data class LiteralExpr(
    val value: Any
) : Expression()

/** Not @Serializable: AST node, the tree holds untyped [LiteralExpr] values. */
data class FieldAccessExpr(
    val path: List<String>
) : Expression()

/** Not @Serializable: AST node, the tree holds untyped [LiteralExpr] values. */
data class ComparisonExpr(
    val left: Expression,
    val op: String,
    val right: Expression
) : Expression()

/** Not @Serializable: AST node, the tree holds untyped [LiteralExpr] values. */
data class LogicalExpr(
    val left: Expression,
    val op: String,
    val right: Expression
) : Expression()

/** Not @Serializable: AST node, the tree holds untyped [LiteralExpr] values. */
data class FunctionCallExpr(
    val name: String,
    val args: List<Expression>
) : Expression()

/** Not @Serializable: AST node, the tree holds untyped [LiteralExpr] values. */
data class ListExpr(
    val elements: List<Expression>
) : Expression()

/** `!expr`. Like every AST node it is not `@Serializable`: [LiteralExpr] holds an untyped `Any`. */
data class NotExpr(
    val operand: Expression
) : Expression()

/** A named call argument, `name = value`, as in `FilePatternRule(pattern = "src")`. Not `@Serializable` (AST node). */
data class NamedArgExpr(
    val name: String,
    val value: Expression
) : Expression()
