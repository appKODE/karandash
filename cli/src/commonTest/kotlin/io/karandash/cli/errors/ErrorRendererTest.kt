package io.karandash.cli.errors

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import io.karandash.api.errors.ErrorCategory
import io.karandash.api.errors.ErrorCode
import io.karandash.api.errors.ErrorSeverity
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorRendererTest {
    // Create a terminal that always outputs ANSI codes for testing
    private fun createTestTerminal(): Terminal = Terminal(ansiLevel = AnsiLevel.TRUECOLOR)

    @Test
    fun `test basic rendering without context`() {
        val errorCode = ErrorCode(
            code = "E0001",
            title = "Configuration Error",
            category = ErrorCategory.CONFIG,
            severity = ErrorSeverity.ERROR,
            messageTemplate = "Invalid configuration detected.",
            suggestion = "Check your config file syntax.",
            docsUrl = "https://karandash.dev/errors/E0001"
        )

        val result = ErrorRenderer.render(errorCode)

        assertTrue(result.contains("E0001"))
        assertTrue(result.contains("Configuration Error"))
        assertTrue(result.contains("Invalid configuration detected."))
        assertTrue(result.contains("Suggestion:"))
        assertTrue(result.contains("Check your config file syntax."))
        assertTrue(result.contains("See: https://karandash.dev/errors/E0001"))
    }

    @Test
    fun `test rendering with file context and line numbers`() {
        val errorCode = ErrorCode(
            code = "E0001",
            title = "Configuration Error",
            category = ErrorCategory.CONFIG,
            severity = ErrorSeverity.ERROR,
            messageTemplate = "Unclosed string literal in pattern value.",
            suggestion = "Add closing \" at the end of line 5.",
            docsUrl = "https://karandash.dev/errors/E0001"
        )

        val sourceLines = listOf(
            "// Config file",
            "",
            "rules {",
            "    titleFormat {",
            "        pattern = \"^(feat|fix)",
            "    }",
            "}"
        )

        val context = ErrorContext(
            filePath = ".karandash/config.kts",
            lineNumber = 5,
            sourceLines = sourceLines
        )

        val result = ErrorRenderer.render(errorCode, context)

        assertTrue(result.contains(".karandash/config.kts:5"))
        assertTrue(result.contains("rules {"))
        assertTrue(result.contains("titleFormat {"))
        assertTrue(result.contains("pattern = \"^(feat|fix)"))
        assertTrue(result.contains("Unclosed string literal in pattern value."))
        assertTrue(result.contains("Add closing \" at the end of line 5."))
    }

    @Test
    fun `test rendering with column number`() {
        val errorCode = ErrorCode(
            code = "E0002",
            title = "Syntax Error",
            category = ErrorCategory.CONFIG,
            severity = ErrorSeverity.ERROR,
            messageTemplate = "Unexpected token.",
            suggestion = null,
            docsUrl = null
        )

        val context = ErrorContext(
            filePath = "config.kts",
            lineNumber = 10,
            columnNumber = 25,
            sourceLines = null
        )

        val result = ErrorRenderer.render(errorCode, context)

        assertTrue(result.contains("config.kts:10:25"))
    }

    @Test
    fun `test color application for ERROR severity`() {
        val errorCode = ErrorCode(
            code = "E0001",
            title = "Error",
            category = ErrorCategory.CONFIG,
            severity = ErrorSeverity.ERROR,
            messageTemplate = "Test error.",
            suggestion = null,
            docsUrl = null
        )

        val terminal = createTestTerminal()
        val result = ErrorRenderer.render(errorCode, terminal = terminal)

        // Check for ANSI escape codes (basic check)
        assertTrue(result.contains("\u001B["))
    }

    @Test
    fun `test color application for WARNING severity`() {
        val errorCode = ErrorCode(
            code = "W0001",
            title = "Warning",
            category = ErrorCategory.CONFIG,
            severity = ErrorSeverity.WARNING,
            messageTemplate = "Test warning.",
            suggestion = null,
            docsUrl = null
        )

        val terminal = createTestTerminal()
        val result = ErrorRenderer.render(errorCode, terminal = terminal)

        // Check for ANSI escape codes
        assertTrue(result.contains("\u001B["))
    }

    @Test
    fun `test color application for INFO severity`() {
        val errorCode = ErrorCode(
            code = "I0001",
            title = "Information",
            category = ErrorCategory.CONFIG,
            severity = ErrorSeverity.INFO,
            messageTemplate = "Test info.",
            suggestion = null,
            docsUrl = null
        )

        val terminal = createTestTerminal()
        val result = ErrorRenderer.render(errorCode, terminal = terminal)

        // Check for ANSI escape codes
        assertTrue(result.contains("\u001B["))
    }

    @Test
    fun `test color application for FATAL severity`() {
        val errorCode = ErrorCode(
            code = "F0001",
            title = "Fatal Error",
            category = ErrorCategory.CONFIG,
            severity = ErrorSeverity.FATAL,
            messageTemplate = "Test fatal.",
            suggestion = null,
            docsUrl = null
        )

        val terminal = createTestTerminal()
        val result = ErrorRenderer.render(errorCode, terminal = terminal)

        // Check for ANSI escape codes
        assertTrue(result.contains("\u001B["))
    }

    @Test
    fun `test rendering without suggestion`() {
        val errorCode = ErrorCode(
            code = "E0001",
            title = "Error",
            category = ErrorCategory.CONFIG,
            severity = ErrorSeverity.ERROR,
            messageTemplate = "Something went wrong.",
            suggestion = null,
            docsUrl = "https://karandash.dev/errors/E0001"
        )

        val result = ErrorRenderer.render(errorCode)

        assertFalse(result.contains("Suggestion:"))
        assertTrue(result.contains("Something went wrong."))
    }

    @Test
    fun `test rendering without docs URL`() {
        val errorCode = ErrorCode(
            code = "E0001",
            title = "Error",
            category = ErrorCategory.CONFIG,
            severity = ErrorSeverity.ERROR,
            messageTemplate = "Something went wrong.",
            suggestion = "Fix it.",
            docsUrl = null
        )

        val result = ErrorRenderer.render(errorCode)

        assertFalse(result.contains("See:"))
        assertTrue(result.contains("Something went wrong."))
        assertTrue(result.contains("Fix it."))
    }

    @Test
    fun `test lineNumber at file start - no before context`() {
        val errorCode = ErrorCode(
            code = "E0001",
            title = "Error",
            category = ErrorCategory.CONFIG,
            severity = ErrorSeverity.ERROR,
            messageTemplate = "Error on first line.",
            suggestion = null,
            docsUrl = null
        )

        val sourceLines = listOf(
            "first line with error",
            "second line",
            "third line",
            "fourth line",
            "fifth line"
        )

        val context = ErrorContext(
            filePath = "test.kts",
            lineNumber = 1,
            sourceLines = sourceLines
        )

        val result = ErrorRenderer.render(errorCode, context)

        assertTrue(result.contains("first line with error"))
        assertTrue(result.contains("second line"))
        assertTrue(result.contains("third line"))
        assertTrue(result.contains("fourth line"))
        // Should show line 1 and 3 lines after (up to line 4)
        assertFalse(result.contains("fifth line")) // Beyond context window
    }

    @Test
    fun `test lineNumber at file end - no after context`() {
        val errorCode = ErrorCode(
            code = "E0001",
            title = "Error",
            category = ErrorCategory.CONFIG,
            severity = ErrorSeverity.ERROR,
            messageTemplate = "Error on last line.",
            suggestion = null,
            docsUrl = null
        )

        val sourceLines = listOf(
            "first line",
            "second line",
            "third line",
            "fourth line",
            "last line with error"
        )

        val context = ErrorContext(
            filePath = "test.kts",
            lineNumber = 5,
            sourceLines = sourceLines
        )

        val result = ErrorRenderer.render(errorCode, context)

        assertTrue(result.contains("last line with error"))
        assertTrue(result.contains("fourth line"))
        assertTrue(result.contains("third line"))
        assertTrue(result.contains("second line"))
        // Should show 3 lines before and the error line
        assertFalse(result.contains("first line")) // Beyond context window
    }

    @Test
    fun `test sourceLines shorter than context window`() {
        val errorCode = ErrorCode(
            code = "E0001",
            title = "Error",
            category = ErrorCategory.CONFIG,
            severity = ErrorSeverity.ERROR,
            messageTemplate = "Error in small file.",
            suggestion = null,
            docsUrl = null
        )

        val sourceLines = listOf(
            "only line with error"
        )

        val context = ErrorContext(
            filePath = "test.kts",
            lineNumber = 1,
            sourceLines = sourceLines
        )

        val result = ErrorRenderer.render(errorCode, context)

        assertTrue(result.contains("only line with error"))
        assertTrue(result.contains("Error in small file."))
    }

    @Test
    fun `test null context`() {
        val errorCode = ErrorCode(
            code = "E0001",
            title = "Error",
            category = ErrorCategory.CONFIG,
            severity = ErrorSeverity.ERROR,
            messageTemplate = "Generic error.",
            suggestion = "Fix it.",
            docsUrl = "https://karandash.dev/errors/E0001"
        )

        val result = ErrorRenderer.render(errorCode, context = null)

        assertTrue(result.contains("E0001"))
        assertTrue(result.contains("Error"))
        assertTrue(result.contains("Generic error."))
        assertTrue(result.contains("Fix it."))
        assertTrue(result.contains("https://karandash.dev/errors/E0001"))
    }

    @Test
    fun `test additional message appended to error message`() {
        val errorCode = ErrorCode(
            code = "E0001",
            title = "Error",
            category = ErrorCategory.CONFIG,
            severity = ErrorSeverity.ERROR,
            messageTemplate = "Invalid value.",
            suggestion = null,
            docsUrl = null
        )

        val context = ErrorContext(
            additionalMessage = "Expected 'string' but got 'number'."
        )

        val result = ErrorRenderer.render(errorCode, context)

        assertTrue(result.contains("Invalid value. Expected 'string' but got 'number'."))
    }

    @Test
    fun `test line numbers are right-aligned to 4 characters`() {
        val errorCode = ErrorCode(
            code = "E0001",
            title = "Error",
            category = ErrorCategory.CONFIG,
            severity = ErrorSeverity.ERROR,
            messageTemplate = "Test",
            suggestion = null,
            docsUrl = null
        )

        val sourceLines = (1..100).map { "line $it" }

        val context = ErrorContext(
            filePath = "test.kts",
            lineNumber = 5,
            sourceLines = sourceLines
        )

        val result = ErrorRenderer.render(errorCode, context)

        // Line numbers should be padded to 4 characters
        assertTrue(result.contains("   2 |")) // Single digit padded to 4
        assertTrue(result.contains("   5 |")) // Error line
    }

    @Test
    fun `test error line marker is present`() {
        val errorCode = ErrorCode(
            code = "E0001",
            title = "Error",
            category = ErrorCategory.CONFIG,
            severity = ErrorSeverity.ERROR,
            messageTemplate = "Test",
            suggestion = null,
            docsUrl = null
        )

        val sourceLines = listOf("line 1", "line 2 error", "line 3")

        val context = ErrorContext(
            filePath = "test.kts",
            lineNumber = 2,
            sourceLines = sourceLines
        )

        val result = ErrorRenderer.render(errorCode, context)

        // The error line should have a '>' marker
        val lines = result.lines()
        val errorLineIndex = lines.indexOfFirst { it.contains("line 2 error") }
        assertTrue(errorLineIndex >= 0)
        // Check that the line with error has the marker in it
        assertTrue(lines[errorLineIndex].contains(">"))
    }

    @Test
    fun `test invalid line number returns empty snippet`() {
        val errorCode = ErrorCode(
            code = "E0001",
            title = "Error",
            category = ErrorCategory.CONFIG,
            severity = ErrorSeverity.ERROR,
            messageTemplate = "Test",
            suggestion = null,
            docsUrl = null
        )

        val sourceLines = listOf("line 1", "line 2", "line 3")

        // Line number out of bounds
        val context = ErrorContext(
            filePath = "test.kts",
            lineNumber = 10,
            sourceLines = sourceLines
        )

        val result = ErrorRenderer.render(errorCode, context)

        // Should still render the error but without the snippet
        assertTrue(result.contains("E0001"))
        assertTrue(result.contains("Test"))
        assertFalse(result.contains("line 1"))
    }

    @Test
    fun `test empty source lines returns empty snippet`() {
        val errorCode = ErrorCode(
            code = "E0001",
            title = "Error",
            category = ErrorCategory.CONFIG,
            severity = ErrorSeverity.ERROR,
            messageTemplate = "Test",
            suggestion = null,
            docsUrl = null
        )

        val context = ErrorContext(
            filePath = "test.kts",
            lineNumber = 1,
            sourceLines = emptyList()
        )

        val result = ErrorRenderer.render(errorCode, context)

        // Should still render the error but without the snippet
        assertTrue(result.contains("E0001"))
        assertTrue(result.contains("Test"))
    }
}
