package io.karandash.cli.errors

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.rendering.TextColors.blue
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextColors.white
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.rendering.TextStyles.underline
import com.github.ajalt.mordant.terminal.Terminal
import io.karandash.api.errors.ErrorCode
import io.karandash.api.errors.ErrorSeverity
import io.karandash.api.errors.KarandashError
import io.karandash.normalize.ConfigKeyNormalizer
import io.karandash.utils.SuggestionEngine
import kotlinx.serialization.Serializable

/**
 * Renders ErrorCode objects into beautiful, actionable terminal output with ANSI styling.
 */
object ErrorRenderer {
    /**
     * Pre-compiled regex for extracting single-quoted values.
     */
    private val SINGLE_QUOTE_REGEX = Regex("'([^']+)'")

    /**
     * Pre-compiled regex for extracting double-quoted values.
     */
    private val DOUBLE_QUOTE_REGEX = Regex("\"([^\"]+)\"")

    /** The first bracketed catalog code in a message, e.g. `[E0010]`, with the space after it. */
    private val BRACKET_CODE_REGEX = Regex("""\[(E\d{4})]\s*""")

    /**
     * Renders a user-facing error [message] that ends a CLI run.
     *
     * A message carrying a catalog code (`[E0010] ...`) renders through [render]: the code and title, the message
     * without the bracket, the catalog suggestion and the docs link. A message without a code is printed as is:
     * no catalog entry fits it, so no docs link is guessed from the exit code.
     */
    fun renderMessage(
        message: String,
        terminal: Terminal = Terminal(ansiLevel = AnsiLevel.NONE),
    ): String {
        val match = BRACKET_CODE_REGEX.find(message)
        val error = match?.let { KarandashError.fromCode(it.groupValues[1]) }
        if (match == null || error == null) {
            return message
        }
        // A catalog suggestion with `{placeholders}` is a template the message already filled in; it is skipped.
        val errorCode = error.toErrorCode().copy(
            messageTemplate = message.removeRange(match.range),
            suggestion = error.suggestion?.takeUnless { '{' in it },
        )
        return render(errorCode, terminal = terminal).trimEnd()
    }

    /**
     * Renders an error code with optional context into a formatted terminal string.
     *
     * @param errorCode The error to render
     * @param context Optional context including file location and source lines
     * @param terminal Terminal instance for ANSI rendering
     * @param suggestionCandidates Optional list of valid values for generating "Did you mean?" suggestions
     * @return Formatted error message with ANSI codes
     */
    fun render(
        errorCode: ErrorCode,
        context: ErrorContext? = null,
        terminal: Terminal = Terminal(),
        suggestionCandidates: List<String>? = null
    ): String {
        val output = StringBuilder()

        val headerStyle = when (errorCode.severity) {
            ErrorSeverity.FATAL, ErrorSeverity.ERROR -> red + bold
            ErrorSeverity.WARNING -> yellow + bold
            ErrorSeverity.INFO -> blue + bold
        }
        output.append(terminal.render(headerStyle("${errorCode.code} ${errorCode.title}")))
        output.append("\n\n")

        if (context?.filePath != null && context.lineNumber != null) {
            val location = formatFileLocation(context.filePath, context.lineNumber, context.columnNumber)
            output.append("  ")
            output.append(terminal.render(dim(white(location))))
            output.append("\n")
        }

        if (context?.sourceLines != null && context.lineNumber != null) {
            val snippet = renderSourceSnippet(context.sourceLines, context.lineNumber, terminal)
            if (snippet.isNotEmpty()) {
                output.append(snippet)
                output.append("\n")
            }
        }

        output.append("  ")
        output.append(errorCode.messageTemplate)
        if (context?.additionalMessage != null) {
            output.append(" ")
            output.append(context.additionalMessage)
        }
        output.append("\n")

        if (suggestionCandidates != null && !suggestionCandidates.isEmpty() && context?.additionalMessage != null) {
            val invalidValue = extractInvalidValue(context.additionalMessage)
            if (invalidValue != null) {
                val suggestions = SuggestionEngine.findClosest(invalidValue, suggestionCandidates)
                if (suggestions.isNotEmpty()) {
                    val suggestion = ConfigKeyNormalizer.camelToKebabCase(suggestions.first())
                    output.append("\n")
                    output.append("  ")
                    output.append(terminal.render(green(bold("Did you mean '$suggestion'?"))))
                    output.append("\n")
                    output.append("  ")
                    output.append(terminal.render(dim("Change to: $suggestion")))
                    output.append("\n")
                }
            }
        }

        if (errorCode.suggestion != null) {
            output.append("\n")
            output.append("  ")
            output.append(terminal.render(green(bold("Suggestion:"))))
            output.append(" ")
            output.append(errorCode.suggestion)
            output.append("\n")
        }

        if (errorCode.docsUrl != null) {
            output.append("\n")
            output.append("  ")
            output.append(terminal.render(cyan(underline("See: ${errorCode.docsUrl}"))))
            output.append("\n")
        }

        return output.toString()
    }

    /**
     * Formats a file location string with optional column number.
     *
     * @param filePath The file path
     * @param lineNumber The line number
     * @param columnNumber Optional column number
     * @return Formatted location string (e.g., "file.txt:42" or "file.txt:42:10")
     */
    private fun formatFileLocation(
        filePath: String,
        lineNumber: Int,
        columnNumber: Int?
    ): String =
        buildString {
            append(filePath)
            append(":")
            append(lineNumber)
            columnNumber?.let {
                append(":")
                append(it)
            }
        }

    /**
     * Extracts the invalid value from an additional message for suggestion generation.
     * Looks for patterns like "invalid value 'xyz'" or quotes in the message.
     *
     * @param message The additional message from ErrorContext
     * @return The extracted invalid value, or null if not found
     */
    private fun extractInvalidValue(message: String): String? {
        val singleQuoteMatch = SINGLE_QUOTE_REGEX.find(message)
        if (singleQuoteMatch != null) {
            return singleQuoteMatch.groupValues[1]
        }

        val doubleQuoteMatch = DOUBLE_QUOTE_REGEX.find(message)
        if (doubleQuoteMatch != null) {
            return doubleQuoteMatch.groupValues[1]
        }

        return null
    }

    /**
     * Renders a source code snippet with line numbers and error highlighting.
     *
     * @param sourceLines All source lines (or relevant excerpt)
     * @param errorLine Line number of the error (1-indexed)
     * @param terminal Terminal instance for ANSI rendering
     * @return Formatted source snippet with context
     */
    private fun renderSourceSnippet(
        sourceLines: List<String>,
        errorLine: Int,
        terminal: Terminal
    ): String {
        if (sourceLines.isEmpty() || errorLine < 1 || errorLine > sourceLines.size) {
            return ""
        }

        val contextBefore = 3
        val contextAfter = 3
        val startLine = maxOf(1, errorLine - contextBefore)
        val endLine = minOf(sourceLines.size, errorLine + contextAfter)

        val output = StringBuilder()
        for (lineNum in startLine..endLine) {
            val isErrorLine = lineNum == errorLine
            val prefix = if (isErrorLine) {
                terminal.render(red(">"))
            } else {
                " "
            }

            val lineNumStr = lineNum.toString().padStart(4)
            val lineNumStyled = terminal.render(dim(white(lineNumStr)))

            val lineContent = sourceLines[lineNum - 1]

            output.append("  ")
            output.append(prefix)
            output.append(" ")
            output.append(lineNumStyled)
            output.append(" | ")
            output.append(lineContent)
            output.append("\n")
        }

        return output.toString()
    }
}

/**
 * Additional context for error rendering, including file location and source code.
 */
@Serializable
data class ErrorContext(
    val filePath: String? = null,
    val lineNumber: Int? = null,
    val columnNumber: Int? = null,
    val sourceLines: List<String>? = null, // Full file content or relevant excerpt
    val additionalMessage: String? = null
)
