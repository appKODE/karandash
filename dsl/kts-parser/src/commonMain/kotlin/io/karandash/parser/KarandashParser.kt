package io.karandash.parser

import io.karandash.api.errors.KarandashError
import io.karandash.api.exception.ConfigParseException
import io.karandash.parser.ast.ConfigAst
import io.karandash.parser.generated.KarandashDslLexer
import io.karandash.parser.generated.KarandashDslParser
import kotlinx.serialization.json.JsonObject
import org.antlr.v4.kotlinruntime.BaseErrorListener
import org.antlr.v4.kotlinruntime.CharStreams
import org.antlr.v4.kotlinruntime.CommonTokenStream
import org.antlr.v4.kotlinruntime.RecognitionException
import org.antlr.v4.kotlinruntime.Recognizer

/**
 * Parses a KTS config: lexing, parsing, AST transformation and interpretation into a JSON tree.
 *
 * @param ruleSchemas rule ID to schema, used to enforce required rule fields
 */
class KarandashParser(
    private val ruleSchemas: Map<String, RuleSchema> = emptyMap(),
) {
    /**
     * Parse a KTS configuration into the same JSON tree a YAML config produces, for the shared
     * `ConfigMapParser` path. `env.VAR` values become `"${VAR}"` placeholders; `if` conditions
     * read the environment, then the config's own `env { }` block (denylisted variables read as unset).
     *
     * @throws ConfigParseException on syntax errors or constructs that have no config meaning
     */
    fun parseToJson(input: String): JsonObject = KtsJsonInterpreter(ruleSchemas, parseToAst(input)).interpret()

    /**
     * Parse a configuration string into an AST.
     * @throws ConfigParseException if the configuration contains syntax errors
     */
    fun parseToAst(input: String): ConfigAst {
        val syntaxErrors = mutableListOf<String>()
        val lexerErrors = mutableListOf<String>()
        val errorListener = object : BaseErrorListener() {
            override fun syntaxError(
                recognizer: Recognizer<*, *>,
                offendingSymbol: Any?,
                line: Int,
                charPositionInLine: Int,
                msg: String,
                e: RecognitionException?
            ) {
                if (recognizer is KarandashDslLexer) lexerErrors.add(msg)
                syntaxErrors.add("line $line:$charPositionInLine $msg")
            }
        }

        // Lexical analysis
        val charStream = CharStreams.fromString(input)
        val lexer = KarandashDslLexer(charStream)
        lexer.removeErrorListeners()
        lexer.addErrorListener(errorListener)

        // Parsing
        val tokens = CommonTokenStream(lexer)
        val parser = KarandashDslParser(tokens)
        parser.removeErrorListeners()
        parser.addErrorListener(errorListener)

        // Parse tree generation
        val parseTree = parser.config()

        // Check for syntax errors with details
        if (syntaxErrors.isNotEmpty()) {
            val details = syntaxErrors.joinToString("; ")
            // a string the lexer could not close gets its catalog code.
            val unclosed = lexerErrors.any { it.startsWith(UNCLOSED_STRING_ERROR) }
            val code = if (unclosed) "${KarandashError.UNCLOSED_STRING_LITERAL.bracketCode} " else ""
            throw ConfigParseException("${code}Syntax errors in configuration: $details")
        }

        // Transform to AST
        val visitor = AntlrToAstVisitor()
        return visitor.visitConfig(parseTree)
    }

    private companion object {
        /** ANTLR's lexer message for a `"` that starts a string token the input never closes. */
        const val UNCLOSED_STRING_ERROR = "token recognition error at: '\""
    }
}
