package io.karandash.parser

import io.karandash.api.exception.ConfigParseException
import io.karandash.config.schema.ConfigSections
import io.karandash.parser.ast.Assignment
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns a KTS [ConfigAst] into the same [JsonObject] tree a YAML config produces, so both formats share
 * the one `ConfigMapParser` interpretation. Nothing is silently dropped: every statement becomes data
 * (unknown sections pass through for the pipeline's unknown-key warnings) or a [ConfigParseException].
 *
 * Mapping: `a = v` is `a: v`; `a { ... }` is an object (repeated blocks merge); `a("key") { ... }` is
 * `key: { ... }`; `a(v)` is `a: v` and `a()` is `a: {}`; `if`/`else` inlines the branch chosen at parse
 * time, reading the environment and then the config's own top-level `env { }` block (parent layers' `env:`
 * blocks are not visible at parse time). `env.X` / `env("X")` values become `${X}` placeholders for the
 * pipeline's interpolation.
 * At the top level `karandash { }` and `options { }` are unwrapped, `policies` / `scripts` blocks become
 * arrays and `profile { rules { } }` becomes `profile.overrides.rules`.
 */
internal class KtsJsonInterpreter(
    private val ruleSchemas: Map<String, RuleSchema>,
    private val ast: ConfigAst,
) {
    private val evaluator = ExpressionEvaluator(envDefaults(ast.items))

    fun interpret(): JsonObject {
        val root = mutableMapOf<String, JsonElement>()
        putTopLevel(ast.items, root)
        return JsonObject(root)
    }

    /** Literal assignments of the top-level `env { }` block (also inside `karandash { }` / `options { }`). */
    private fun envDefaults(stmts: List<Statement>): Map<String, String> =
        stmts
            .filterIsInstance<FunctionCall>()
            .filter { it.args.isEmpty() }
            .flatMap { call ->
                val inner = call.block?.statements.orEmpty()
                when (call.name) {
                    ConfigSections.ENV -> inner.filterIsInstance<Assignment>().mapNotNull { a ->
                        (a.value as? LiteralExpr)?.let { a.name to it.value.toString() }
                    }

                    WRAPPER, ConfigSections.OPTIONS -> envDefaults(inner).toList()

                    else -> emptyList()
                }
            }.toMap()

    private fun putTopLevel(
        stmts: List<Statement>,
        out: MutableMap<String, JsonElement>,
    ) {
        flatten(stmts).forEach { stmt ->
            val call = stmt as? FunctionCall
            val block = call?.block
            if (call == null || block == null || call.args.isNotEmpty()) {
                put(stmt, out)
            } else {
                when (call.name) {
                    WRAPPER, ConfigSections.OPTIONS -> putTopLevel(block.statements, out)
                    ConfigSections.POLICIES -> out.append(call.name, listEntries(call, pathDefault = false))
                    ConfigSections.SCRIPTS -> out.append(call.name, listEntries(call, pathDefault = true))
                    ConfigSections.PROFILE -> out.mergeIn(call.name, profile(objectOf(block.statements)))
                    ConfigSections.RULES -> out.mergeIn(call.name, checkRequired(objectOf(block.statements)))
                    else -> put(call, out)
                }
            }
        }
    }

    private fun put(
        stmt: Statement,
        out: MutableMap<String, JsonElement>,
    ) {
        when (stmt) {
            is Assignment -> {
                out[stmt.name] = value(stmt.value)
            }

            is FunctionCall -> {
                val block = stmt.block
                if (block == null) {
                    out.mergeIn(stmt.name, statementCallValue(stmt))
                } else {
                    out.mergeIn(blockKey(stmt), objectOf(block.statements))
                }
            }

            is IfStatement -> {
                flatten(listOf(stmt)).forEach { put(it, out) }
            }
        }
    }

    private fun objectOf(stmts: List<Statement>): JsonObject {
        val out = mutableMapOf<String, JsonElement>()
        flatten(stmts).forEach { put(it, out) }
        return JsonObject(out)
    }

    /** Replaces each `if` with the statements of the branch its condition selects. */
    private fun flatten(stmts: List<Statement>): List<Statement> =
        stmts.flatMap { stmt ->
            if (stmt is IfStatement) flatten(evaluator.evaluateIfBranch(stmt).orEmpty()) else listOf(stmt)
        }

    /** `name { }` is keyed by `name`; `rule("id") { }` is keyed by `id`. */
    private fun blockKey(call: FunctionCall): String =
        when (call.args.size) {
            0 -> call.name
            1 -> evaluator.evalString(call.args.single())
            else -> throw ConfigParseException("'${call.name}(...) { }' takes at most one key argument, got ${call.args.size}")
        }

    /** Entries of a `policies { }` / `scripts { }` block: each `name { }` is one object, `name("x")` one value. */
    private fun listEntries(
        call: FunctionCall,
        pathDefault: Boolean,
    ): List<JsonElement> =
        flatten(call.block?.statements.orEmpty()).map { stmt ->
            val entry = stmt as? FunctionCall
                ?: throw ConfigParseException("'${call.name}' entries must be blocks such as 'entry { ... }', got $stmt")
            val block = entry.block
            when {
                block == null -> statementCallValue(entry)
                pathDefault -> JsonObject(mapOf(PATH to JsonPrimitive(entry.name)) + objectOf(block.statements))
                else -> objectOf(block.statements)
            }
        }

    /** `profile { id = "x"; rules { ... } }` is the YAML `profile: {id: x, overrides: {rules: ...}}`. */
    private fun profile(obj: JsonObject): JsonObject {
        val rules = obj[ConfigSections.RULES] ?: return obj
        val overrides = mutableMapOf<String, JsonElement>()
        (obj[OVERRIDES] as? JsonObject)?.let { overrides.putAll(it) }
        overrides.mergeIn(ConfigSections.RULES, rules)
        return JsonObject(obj - ConfigSections.RULES + (OVERRIDES to JsonObject(overrides)))
    }

    private fun checkRequired(rules: JsonObject): JsonObject {
        rules.forEach { (ruleId, ruleConfig) ->
            val fields = (ruleConfig as? JsonObject)?.keys.orEmpty()
            ruleSchemas[ruleId]?.required?.forEach { (field, message) ->
                if (field !in fields) throw ConfigParseException(message)
            }
        }
        return rules
    }

    private fun value(expr: Expression): JsonElement =
        when (expr) {
            is LiteralExpr -> literal(expr.value)
            is ListExpr -> JsonArray(expr.elements.map(::value))
            is FieldAccessExpr -> JsonPrimitive(evaluator.evalString(expr))
            is FunctionCallExpr -> exprCallValue(expr)
            is ComparisonExpr, is LogicalExpr, is NotExpr -> JsonPrimitive(evaluator.evalCondition(expr))
            is NamedArgExpr -> throw ConfigParseException("Named argument '${expr.name} = ...' outside a call")
        }

    /** A value-position call: `listOf(...)`, `env("X")` or a named-argument object such as `FilePatternRule(pattern = "a")`. */
    private fun exprCallValue(expr: FunctionCallExpr): JsonElement {
        val args = expr.args
        return when {
            expr.name == LIST_OF -> JsonArray(args.map(::value))
            expr.name == ENV && args.size == 1 -> JsonPrimitive("\${${evaluator.evalString(args.single())}}")
            args.isNotEmpty() && args.all { it is NamedArgExpr } -> namedObject(args)
            else -> throw ConfigParseException("Unsupported function call '${expr.name}(...)' in a value")
        }
    }

    /** A statement call without a block: `a()` is `{}`, `a(v)` is `v`, `a(v, w)` is `[v, w]`, `a(k = v)` an object. */
    private fun statementCallValue(call: FunctionCall): JsonElement {
        val args = call.args
        val named = args.count { it is NamedArgExpr }
        return when {
            args.isEmpty() -> JsonObject(emptyMap())
            named == args.size -> namedObject(args)
            named > 0 -> throw ConfigParseException("'${call.name}(...)' mixes named and positional arguments")
            args.size == 1 -> value(args.single())
            else -> JsonArray(args.map(::value))
        }
    }

    private fun namedObject(args: List<Expression>): JsonObject =
        JsonObject(args.filterIsInstance<NamedArgExpr>().associate { it.name to value(it.value) })

    private fun literal(raw: Any): JsonPrimitive =
        when (raw) {
            is Boolean -> JsonPrimitive(raw)
            is Number -> JsonPrimitive(raw)
            else -> JsonPrimitive(raw.toString())
        }

    private fun MutableMap<String, JsonElement>.append(
        key: String,
        entries: List<JsonElement>,
    ) {
        this[key] = JsonArray((this[key] as? JsonArray).orEmpty() + entries)
    }

    /** Repeated blocks merge recursively (`ai { a {} }` then `ai { b {} }`); anything else replaces. */
    private fun MutableMap<String, JsonElement>.mergeIn(
        key: String,
        value: JsonElement,
    ) {
        this[key] = merge(this[key], value)
    }

    private fun merge(
        old: JsonElement?,
        new: JsonElement,
    ): JsonElement {
        if (old !is JsonObject || new !is JsonObject) return new
        val out = old.toMutableMap()
        new.forEach { (k, v) -> out[k] = merge(out[k], v) }
        return JsonObject(out)
    }

    private companion object {
        const val WRAPPER = "karandash"
        const val OVERRIDES = "overrides"
        const val PATH = "path"
        const val LIST_OF = "listOf"
        const val ENV = "env"
    }
}
