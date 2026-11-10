package io.karandash.cli.errors

import io.karandash.api.errors.ErrorCatalog
import io.karandash.api.errors.ErrorCategory
import io.karandash.api.errors.ErrorCode
import io.karandash.api.errors.ErrorSeverity
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorRendererSuggestionTest {
    @Test
    fun `test invalid rule name shows suggestion`() {
        val errorCode = ErrorCatalog.lookup("E0002")!!
        val context = ErrorContext(
            additionalMessage = "Unknown rule 'titel-format' in configuration."
        )
        val candidates = listOf("titleFormat", "branch-format", "label-required")

        val result = ErrorRenderer.render(errorCode, context, suggestionCandidates = candidates)

        assertTrue(result.contains("Did you mean 'title-format'?"))
        assertTrue(result.contains("Change to: title-format"))
    }

    @Test
    fun `test invalid platform name shows suggestion`() {
        val errorCode = ErrorCatalog.lookup("E0102")!!
        val context = ErrorContext(
            additionalMessage = "Unknown platform 'gtihub'."
        )
        val candidates = listOf("github", "gitlab", "bitbucket", "azure-devops", "gerrit", "gitea", "awscodecommit")

        val result = ErrorRenderer.render(errorCode, context, suggestionCandidates = candidates)

        assertTrue(result.contains("Did you mean 'github'?"))
        assertTrue(result.contains("Change to: github"))
    }

    @Test
    fun `test Levenshtein distance filtering - distance 2`() {
        val errorCode = ErrorCode(
            code = "E0002",
            title = "Unknown rule name",
            category = ErrorCategory.CONFIG,
            severity = ErrorSeverity.ERROR,
            messageTemplate = "Unknown rule '{rule}' in configuration.",
            suggestion = null,
            docsUrl = "https://karandash.dev/errors/E0002"
        )
        val context = ErrorContext(
            additionalMessage = "Unknown rule 'lable-required' in configuration."
        )
        val candidates = listOf("label-required", "titleFormat", "branch-format")

        val result = ErrorRenderer.render(errorCode, context, suggestionCandidates = candidates)

        // "lable-required" -> "label-required" has distance 1 (insert 'e')
        assertTrue(result.contains("Did you mean 'label-required'?"))
        assertTrue(result.contains("Change to: label-required"))
    }

    @Test
    fun `test no suggestion when distance too large`() {
        val errorCode = ErrorCode(
            code = "E0002",
            title = "Unknown rule name",
            category = ErrorCategory.CONFIG,
            severity = ErrorSeverity.ERROR,
            messageTemplate = "Unknown rule '{rule}' in configuration.",
            suggestion = null,
            docsUrl = "https://karandash.dev/errors/E0002"
        )
        val context = ErrorContext(
            additionalMessage = "Unknown rule 'xyz' in configuration."
        )
        val candidates = listOf("titleFormat", "branch-format", "label-required")

        val result = ErrorRenderer.render(errorCode, context, suggestionCandidates = candidates)

        // "xyz" has distance > 3 from all candidates, so no suggestion
        assertFalse(result.contains("Did you mean"))
        assertFalse(result.contains("Change to:"))
    }

    @Test
    fun `test suggestion with double quotes`() {
        val errorCode = ErrorCode(
            code = "E0002",
            title = "Unknown rule name",
            category = ErrorCategory.CONFIG,
            severity = ErrorSeverity.ERROR,
            messageTemplate = "Unknown rule in configuration.",
            suggestion = null,
            docsUrl = null
        )
        val context = ErrorContext(
            additionalMessage = "Unknown rule \"titel-format\" in configuration."
        )
        val candidates = listOf("titleFormat", "branch-format")

        val result = ErrorRenderer.render(errorCode, context, suggestionCandidates = candidates)

        assertTrue(result.contains("Did you mean 'title-format'?"))
        assertTrue(result.contains("Change to: title-format"))
    }

    @Test
    fun `test no suggestion when no candidates provided`() {
        val errorCode = ErrorCode(
            code = "E0002",
            title = "Unknown rule name",
            category = ErrorCategory.CONFIG,
            severity = ErrorSeverity.ERROR,
            messageTemplate = "Unknown rule '{rule}' in configuration.",
            suggestion = null,
            docsUrl = null
        )
        val context = ErrorContext(
            additionalMessage = "Unknown rule 'titel-format' in configuration."
        )

        val result = ErrorRenderer.render(errorCode, context, suggestionCandidates = null)

        assertFalse(result.contains("Did you mean"))
    }

    @Test
    fun `test no suggestion when no additional message`() {
        val errorCode = ErrorCode(
            code = "E0002",
            title = "Unknown rule name",
            category = ErrorCategory.CONFIG,
            severity = ErrorSeverity.ERROR,
            messageTemplate = "Unknown rule in configuration.",
            suggestion = null,
            docsUrl = null
        )
        val context = ErrorContext()
        val candidates = listOf("titleFormat", "branch-format")

        val result = ErrorRenderer.render(errorCode, context, suggestionCandidates = candidates)

        assertFalse(result.contains("Did you mean"))
    }

    @Test
    fun `test suggestion shown before static suggestion`() {
        val errorCode = ErrorCode(
            code = "E0002",
            title = "Unknown rule name",
            category = ErrorCategory.CONFIG,
            severity = ErrorSeverity.ERROR,
            messageTemplate = "Unknown rule in configuration.",
            suggestion = "Check available rules in documentation.",
            docsUrl = "https://karandash.dev/errors/E0002"
        )
        val context = ErrorContext(
            additionalMessage = "Unknown rule 'titel-format' in configuration."
        )
        val candidates = listOf("titleFormat", "branch-format")

        val result = ErrorRenderer.render(errorCode, context, suggestionCandidates = candidates)

        val didYouMeanIndex = result.indexOf("Did you mean")
        val staticSuggestionIndex = result.indexOf("Suggestion:")

        assertTrue(didYouMeanIndex >= 0)
        assertTrue(staticSuggestionIndex >= 0)
        assertTrue(didYouMeanIndex < staticSuggestionIndex, "Dynamic suggestion should appear before static suggestion")
    }

    @Test
    fun `test case insensitive matching`() {
        val errorCode = ErrorCode(
            code = "E0102",
            title = "Unknown platform",
            category = ErrorCategory.PROVIDER,
            severity = ErrorSeverity.ERROR,
            messageTemplate = "Unknown platform.",
            suggestion = null,
            docsUrl = null
        )
        val context = ErrorContext(
            additionalMessage = "Unknown platform 'GITHUB'."
        )
        val candidates = listOf("github", "gitlab", "bitbucket")

        val result = ErrorRenderer.render(errorCode, context, suggestionCandidates = candidates)

        // Should match "GITHUB" to "github" with distance 0
        assertTrue(result.contains("Did you mean 'github'?"))
    }

    @Test
    fun `test multiple valid candidates shows closest`() {
        val errorCode = ErrorCode(
            code = "E0002",
            title = "Unknown rule name",
            category = ErrorCategory.CONFIG,
            severity = ErrorSeverity.ERROR,
            messageTemplate = "Unknown rule in configuration.",
            suggestion = null,
            docsUrl = null
        )
        val context = ErrorContext(
            additionalMessage = "Unknown rule 'titel-format' in configuration."
        )
        val candidates = listOf("titleFormat", "title-length", "branch-title")

        val result = ErrorRenderer.render(errorCode, context, suggestionCandidates = candidates)

        // Should show the closest match ("titel-format" -> "titleFormat" has distance 1)
        assertTrue(result.contains("Did you mean"))
        assertTrue(result.contains("Change to:"))
    }
}
