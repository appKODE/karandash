package io.karandash.cli.errors

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import io.karandash.api.errors.ErrorCategory
import io.karandash.api.errors.ErrorCode
import io.karandash.api.errors.ErrorSeverity
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A context that carries only some fields renders what it has and leaves out the rest. */
class ErrorRendererPartialContextTest {
    private val plain = Terminal(ansiLevel = AnsiLevel.NONE)

    private fun code(severity: ErrorSeverity = ErrorSeverity.ERROR) =
        ErrorCode(
            code = "E0042",
            title = "Bad Key",
            category = ErrorCategory.CONFIG,
            severity = severity,
            messageTemplate = "Unknown key.",
            suggestion = null,
            docsUrl = null,
        )

    private fun render(
        context: ErrorContext?,
        candidates: List<String>? = null,
        severity: ErrorSeverity = ErrorSeverity.ERROR,
    ) = ErrorRenderer.render(code(severity), context, plain, candidates)

    @Test
    fun `a location needs both the file and the line`() {
        assertFalse("cfg.yaml" in render(ErrorContext(filePath = "cfg.yaml")))
        assertFalse(":3" in render(ErrorContext(lineNumber = 3)))
        assertContains(render(ErrorContext(filePath = "cfg.yaml", lineNumber = 3)), "cfg.yaml:3\n")
        assertContains(render(ErrorContext(filePath = "cfg.yaml", lineNumber = 3, columnNumber = 7)), "cfg.yaml:3:7")
    }

    @Test
    fun `a snippet is left out when the line is outside the source`() {
        val lines = listOf("a: 1", "b: 2")
        assertFalse(" | " in render(ErrorContext(sourceLines = lines, lineNumber = 0)))
        assertFalse(" | " in render(ErrorContext(sourceLines = lines, lineNumber = 3)))
        assertFalse(" | " in render(ErrorContext(sourceLines = emptyList(), lineNumber = 1)))
        assertFalse(" | " in render(ErrorContext(sourceLines = lines)))
        val snippet = render(ErrorContext(sourceLines = lines, lineNumber = 2))
        assertContains(snippet, ">    2 | b: 2")
        assertContains(snippet, "     1 | a: 1")
    }

    @Test
    fun `suggestions need candidates a quoted value and a close match`() {
        val candidates = listOf("titleFormat", "sizeLimits")
        assertContains(render(ErrorContext(additionalMessage = "key 'titleFormt'"), candidates), "Did you mean 'title-format'?")
        assertContains(render(ErrorContext(additionalMessage = "key \"sizeLimit\""), candidates), "Change to: size-limits")
        assertFalse("Did you mean" in render(ErrorContext(additionalMessage = "key titleFormt"), candidates))
        assertFalse("Did you mean" in render(ErrorContext(additionalMessage = "key 'zzzzzzzzzzzz'"), candidates))
        assertFalse("Did you mean" in render(ErrorContext(additionalMessage = "key 'titleFormt'"), emptyList()))
        assertFalse("Did you mean" in render(ErrorContext(), candidates))
        assertFalse("Did you mean" in render(null, candidates))
    }

    @Test
    fun `every severity renders its header without suggestion or docs`() {
        ErrorSeverity.entries.forEach { severity ->
            val text = render(null, severity = severity)
            assertTrue(text.startsWith("E0042 Bad Key"), text)
            assertFalse("Suggestion:" in text || "See:" in text, text)
        }
    }
}
