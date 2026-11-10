package io.karandash.plugins.ai.core.pipeline

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.sdk.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiGroundingVerifierTest {
    private val defaultVerifier = AiGroundingVerifier()

    private class CapturingLogger : Logger {
        val messages = mutableListOf<Pair<String, String>>()

        override val isDebugEnabled: Boolean get() = true

        override fun debug(message: String) {
            messages.add("debug" to message)
        }

        override fun info(message: String) {
            messages.add("info" to message)
        }

        override fun warn(message: String) {
            messages.add("warn" to message)
        }

        override fun error(message: String) {
            messages.add("error" to message)
        }
    }

    private fun makeMr(vararg changes: FileChange): MergeRequest =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "Test MR",
            description = null,
            sourceBranch = "feature",
            targetBranch = "main",
            author = User(username = "dev", name = "Developer"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = changes.toList(),
        )

    private fun aiFile(
        message: String,
        path: String = "src/Foo.kt",
        line: Int? = 10,
        explanation: String? = null,
    ) = Finding(
        severity = Severity.WARNING,
        message = message,
        rule = "ai-review",
        location = Location.FileRef(path, line),
        explanation = explanation,
    )

    private fun nonAiFinding(message: String = "Non-AI finding") =
        Finding(
            severity = Severity.ERROR,
            message = message,
            rule = "title-format",
            location = Location.FileRef("src/Foo.kt", 5),
        )

    private fun fileChange(
        path: String,
        diffContent: String,
        changeType: ChangeType = ChangeType.MODIFIED,
    ) = FileChange(path = path, changeType = changeType, diffContent = diffContent)

    // ── Pass-through cases ────────────────────────────────────────────────

    @Test
    fun `non-AI findings pass through unchanged`() {
        val finding = nonAiFinding()
        val mr = makeMr(fileChange("src/Foo.kt", "some diff"))

        val result = defaultVerifier.verify(listOf(finding), mr)

        assertEquals(1, result.findings.size)
        assertEquals(finding, result.findings[0])
        assertEquals(0, result.totalAiFindings)
    }

    @Test
    fun `AI findings with no file location pass through unchanged`() {
        val finding = Finding(
            severity = Severity.WARNING,
            message = "Summary finding",
            rule = "mr-summary",
        )
        val mr = makeMr(fileChange("src/Foo.kt", "some diff"))

        val result = defaultVerifier.verify(listOf(finding), mr)

        assertEquals(1, result.findings.size)
        assertEquals(finding, result.findings[0])
        assertEquals(0, result.refutedCount)
    }

    @Test
    fun `AI finding referencing file not in MR changes passes through unchanged`() {
        val finding = aiFile("Missing `nullCheck` here", path = "src/Other.kt")
        val mr = makeMr(fileChange("src/Foo.kt", "fun nullCheck() {}"))

        val result = defaultVerifier.verify(listOf(finding), mr)

        assertEquals(1, result.findings.size)
        assertEquals(Severity.WARNING, result.findings[0].severity)
        assertEquals(0, result.refutedCount)
    }

    @Test
    fun `disabled config passes all findings unchanged`() {
        val verifier = AiGroundingVerifier(GroundingConfig(enabled = false))
        val finding = aiFile("Uses `nonExistentSymbol` here")
        val mr = makeMr(fileChange("src/Foo.kt", "fun something() {}"))

        val result = verifier.verify(listOf(finding), mr)

        assertEquals(1, result.findings.size)
        assertEquals(Severity.WARNING, result.findings[0].severity)
        assertEquals(0, result.refutedCount)
    }

    // ── Symbol existence check ────────────────────────────────────────────

    @Test
    fun `finding with backtick symbol present in diff is kept`() {
        val finding = aiFile("Missing null check for `getUserById`")
        val mr = makeMr(fileChange("src/Foo.kt", "+fun getUserById(id: String) = null"))

        val result = defaultVerifier.verify(listOf(finding), mr)

        assertEquals(Severity.WARNING, result.findings[0].severity)
        assertFalse(result.findings[0].message.startsWith("[REFUTED"))
        assertEquals(0, result.refutedCount)
    }

    @Test
    fun `finding with backtick symbol absent from diff is dropped`() {
        val finding = aiFile("Missing null check for `phantomMethod`")
        val mr = makeMr(fileChange("src/Foo.kt", "+fun realMethod() = 42"))

        val result = defaultVerifier.verify(listOf(finding), mr)

        assertTrue(result.findings.isEmpty())
        assertEquals(1, result.refutedCount)
        assertEquals(1, result.totalAiFindings)
    }

    @Test
    fun `symbol check disabled passes finding with missing symbol`() {
        val verifier = AiGroundingVerifier(GroundingConfig(checkSymbolExistence = false))
        val finding = aiFile("Uses `phantomMethod` here")
        val mr = makeMr(fileChange("src/Foo.kt", "+fun realMethod() {}"))

        val result = verifier.verify(listOf(finding), mr)

        assertEquals(Severity.WARNING, result.findings[0].severity)
        assertEquals(0, result.refutedCount)
    }

    @Test
    fun `symbol from another file of the MR grounds the finding`() {
        val finding = aiFile("`splitTopLevel` is called with the raw header here")
        val mr = makeMr(
            fileChange("src/Foo.kt", "+val parts = parse(header)"),
            fileChange("src/Parser.kt", "+fun splitTopLevel(s: String) = s.split(',')"),
        )

        val result = defaultVerifier.verify(listOf(finding), mr)

        assertEquals(0, result.refutedCount)
        assertEquals(1, result.findings.size)
    }

    @Test
    fun `proposed fix snippet is grounded by its receiver in the diff`() {
        val finding = aiFile("`rules.all {}` is true on an empty list - check `rules.isEmpty()` first")
        val mr = makeMr(fileChange("src/Foo.kt", "+val ok = rules.all { it.matches(photo) }"))

        val result = defaultVerifier.verify(listOf(finding), mr)

        assertEquals(0, result.refutedCount)
    }

    @Test
    fun `extractSymbols skips notation that is not a name`() {
        val symbols = AiGroundingVerifier.extractSymbols("Returns `[]` for `[Optional]` when `!= null` fails in `load()`")
        assertEquals(listOf("load()"), symbols)
    }

    // ── Code snippet check ────────────────────────────────────────────────

    @Test
    fun `finding with code snippet present in diff is kept`() {
        val finding = aiFile("Avoid using `user.getName()` directly")
        val mr = makeMr(fileChange("src/Foo.kt", "+val name = user.getName()"))

        val result = defaultVerifier.verify(listOf(finding), mr)

        assertEquals(Severity.WARNING, result.findings[0].severity)
        assertFalse(result.findings[0].message.startsWith("[REFUTED"))
        assertEquals(0, result.refutedCount)
    }

    @Test
    fun `finding with code snippet absent from diff is dropped`() {
        // `foo.bar()` is extracted as both a symbol and a code snippet - both checks fail
        val finding = aiFile("Replace `foo.bar()` with safer alternative")
        val mr = makeMr(fileChange("src/Foo.kt", "+val x = baz.qux()"))

        val result = defaultVerifier.verify(listOf(finding), mr)

        assertTrue(result.findings.isEmpty())
        assertEquals(1, result.refutedCount)
    }

    @Test
    fun `code snippet check also inspects explanation field`() {
        val finding = aiFile(
            message = "Potential issue detected",
            explanation = "The call `service.doThing()` lacks error handling",
        )
        val mr = makeMr(fileChange("src/Foo.kt", "+service.doThing()"))

        val result = defaultVerifier.verify(listOf(finding), mr)

        assertEquals(Severity.WARNING, result.findings[0].severity)
        assertEquals(0, result.refutedCount)
    }

    @Test
    fun `code snippet check disabled still runs symbol check`() {
        // With code snippets disabled, `foo.bar()` is still extracted as a symbol
        // and the symbol-existence check fails because it's not in the diff
        val verifier = AiGroundingVerifier(GroundingConfig(checkCodeSnippets = false, failureMode = GroundingConfig.FAILURE_MODE_DOWNGRADE))
        val finding = aiFile("Replace `foo.bar()` with something else")
        val mr = makeMr(fileChange("src/Foo.kt", "+val x = baz.qux()"))

        val result = verifier.verify(listOf(finding), mr)

        assertEquals(Severity.INFO, result.findings[0].severity)
        assertTrue(result.findings[0].message.contains("symbol-existence"))
        assertEquals(1, result.refutedCount)
    }

    @Test
    fun `code snippet check disabled passes when symbol exists in diff`() {
        val verifier = AiGroundingVerifier(GroundingConfig(checkCodeSnippets = false))
        val finding = aiFile("Replace `baz.qux()` with something else")
        val mr = makeMr(fileChange("src/Foo.kt", "+val x = baz.qux()"))

        val result = verifier.verify(listOf(finding), mr)

        assertEquals(Severity.WARNING, result.findings[0].severity)
        assertEquals(0, result.refutedCount)
    }

    // ── Both checks fail ──────────────────────────────────────────────────

    @Test
    fun `both checks failing produces combined REFUTED prefix`() {
        val finding = aiFile("Use `phantomMethod` via `obj.call()` pattern")
        val mr = makeMr(fileChange("src/Foo.kt", "+fun realThing() {}"))

        val result = defaultVerifier.verify(listOf(finding), mr)

        assertTrue(result.findings.isEmpty())
        assertEquals(1, result.refutedCount)
    }

    // ── Counts ────────────────────────────────────────────────────────────

    @Test
    fun `GroundingResult counts are correct with mixed findings`() {
        val ai1 = aiFile("Missing `phantomMethod`", path = "src/A.kt")
        val ai2 = aiFile("Uses `realMethod`", path = "src/B.kt")
        val nonAi = nonAiFinding()

        val mr = makeMr(
            fileChange("src/A.kt", "+fun nothingHere() {}"),
            fileChange("src/B.kt", "+fun realMethod() {}"),
            fileChange("src/Foo.kt", "unchanged"),
        )

        val result = defaultVerifier.verify(listOf(ai1, ai2, nonAi), mr)

        assertEquals(2, result.totalAiFindings)
        assertEquals(1, result.refutedCount)
        assertEquals(2, result.findings.size)
    }

    @Test
    fun `empty findings list returns empty result`() {
        val mr = makeMr()
        val result = defaultVerifier.verify(emptyList(), mr)

        assertEquals(0, result.findings.size)
        assertEquals(0, result.refutedCount)
        assertEquals(0, result.totalAiFindings)
    }

    // ── extractSymbols ────────────────────────────────────────────────────

    @Test
    fun `extractSymbols returns backtick-quoted identifiers`() {
        val symbols = AiGroundingVerifier.extractSymbols("Call `getUserById` to fetch users")
        assertEquals(listOf("getUserById"), symbols)
    }

    @Test
    fun `extractSymbols returns multiple backtick identifiers`() {
        val symbols = AiGroundingVerifier.extractSymbols("Replace `oldFunc` with `newFunc`")
        assertEquals(listOf("oldFunc", "newFunc"), symbols)
    }

    @Test
    fun `extractProseSymbols reads camelCase identifiers`() {
        val symbols = AiGroundingVerifier.extractProseSymbols("The UserService should handle nullability")
        assertTrue(symbols.contains("UserService"))
    }

    @Test
    fun `extractProseSymbols skips all-caps acronyms and category labels`() {
        assertTrue(AiGroundingVerifier.extractProseSymbols("Code suggestion (BUG)").isEmpty())
        assertEquals(listOf("ApiKeyInterceptor"), AiGroundingVerifier.extractProseSymbols("the API key is hardcoded in ApiKeyInterceptor"))
    }

    @Test
    fun `extractProseSymbols skips sentence-capitalized prose words`() {
        assertTrue(AiGroundingVerifier.extractProseSymbols("Live API key committed to source").isEmpty())
        assertTrue(AiGroundingVerifier.extractProseSymbols("Non-null assertion on a nullable lookup throws").isEmpty())
        assertEquals(listOf("OrderService"), AiGroundingVerifier.extractProseSymbols("Crash in OrderService on cancel"))
    }

    @Test
    fun `extractProseSymbols filters common English words from camelCase fallback`() {
        val symbols = AiGroundingVerifier.extractProseSymbols("The function should return value")
        assertTrue(symbols.none { it.lowercase() == "function" })
        assertTrue(symbols.none { it.lowercase() == "return" })
        assertTrue(symbols.none { it.lowercase() == "value" })
    }

    @Test
    fun `extractSymbols ignores plain text backtick content without operator-like chars`() {
        val symbols = AiGroundingVerifier.extractSymbols("Missing check for `foo`")
        assertEquals(listOf("foo"), symbols)
    }

    @Test
    fun `extractProseSymbols returns empty list for plain sentences without identifiers`() {
        val symbols = AiGroundingVerifier.extractProseSymbols("this code has an issue")
        assertTrue(symbols.isEmpty())
    }

    @Test
    fun `extractProseSymbols skips acronym plurals`() {
        assertTrue(AiGroundingVerifier.extractProseSymbols("Signed APKs and URLs keep their IDs").isEmpty())
    }

    @Test
    fun `extractSymbols reads only backticks`() {
        assertTrue(AiGroundingVerifier.extractSymbols("Crash in OrderService on cancel").isEmpty())
    }

    @Test
    fun `prose name absent from diff lowers the finding but never drops it`() {
        val finding = aiFile("Release builds upload unsigned APKs from ArtifactUploader")
        val mr = makeMr(fileChange("src/Foo.kt", "+fun upload(file: String) = Unit"))

        val result = defaultVerifier.verify(listOf(finding), mr)

        assertEquals(0, result.refutedCount)
        assertEquals(Severity.INFO, result.findings.single().severity)
        assertFalse(
            result.findings
                .single()
                .message
                .startsWith("[REFUTED")
        )
    }

    @Test
    fun `backtick symbol from the full text of a changed file grounds the finding`() {
        val finding = aiFile("`retryLimit` is never read after this change")
        val change = FileChange(
            path = "src/Foo.kt",
            changeType = ChangeType.MODIFIED,
            diffContent = "+val x = 1",
            newContent = "val retryLimit = 3\nval x = 1",
        )

        val result = defaultVerifier.verify(listOf(finding), makeMr(change))

        assertEquals(0, result.refutedCount)
        assertEquals(Severity.WARNING, result.findings.single().severity)
    }

    // ── extractCodeSnippets ───────────────────────────────────────────────

    @Test
    fun `extractCodeSnippets extracts method call with dot notation`() {
        val snippets = AiGroundingVerifier.extractCodeSnippets("Avoid `user.getName()` here")
        assertEquals(listOf("user.getName()"), snippets)
    }

    @Test
    fun `extractCodeSnippets extracts expression with equals sign`() {
        val snippets = AiGroundingVerifier.extractCodeSnippets("Replace `x = null` with safe pattern")
        assertEquals(listOf("x = null"), snippets)
    }

    @Test
    fun `extractCodeSnippets extracts function reference`() {
        val snippets = AiGroundingVerifier.extractCodeSnippets("Use `String::trim` instead")
        assertEquals(listOf("String::trim"), snippets)
    }

    @Test
    fun `extractCodeSnippets extracts lambda arrow`() {
        val snippets = AiGroundingVerifier.extractCodeSnippets("Simplify `{ x -> x.name }`")
        assertEquals(listOf("{ x -> x.name }"), snippets)
    }

    @Test
    fun `extractCodeSnippets ignores plain word backtick content`() {
        val snippets = AiGroundingVerifier.extractCodeSnippets("The `UserService` class is missing")
        assertEquals(emptyList(), snippets)
    }

    @Test
    fun `extractCodeSnippets ignores short backtick content`() {
        val snippets = AiGroundingVerifier.extractCodeSnippets("Check `x` value")
        assertEquals(emptyList(), snippets)
    }

    @Test
    fun `extractCodeSnippets returns empty for text without backticks`() {
        val snippets = AiGroundingVerifier.extractCodeSnippets("No backticks here at all")
        assertEquals(emptyList(), snippets)
    }

    // ── Logging tests ────────────────────────────────────────────────────

    @Test
    fun `logs summary of grounding checks`() {
        val logger = CapturingLogger()
        val verifier = AiGroundingVerifier(logger = logger)
        val finding = aiFile("Missing `realMethod` call")
        val mr = makeMr(fileChange("src/Foo.kt", "+fun realMethod() {}"))

        verifier.verify(listOf(finding), mr)

        val debugMessages = logger.messages.filter { it.first == "debug" }.map { it.second }
        assertTrue(debugMessages.any { it.contains("Grounding: checking 1 AI finding") })
        assertTrue(debugMessages.any { it.contains("checked 1") && it.contains("refuted 0") })
    }

    @Test
    fun `logs per-finding detail when symbol check fails`() {
        val logger = CapturingLogger()
        val verifier = AiGroundingVerifier(logger = logger)
        val finding = aiFile("Missing `phantomMethod` call")
        val mr = makeMr(fileChange("src/Foo.kt", "+fun realMethod() {}"))

        verifier.verify(listOf(finding), mr)

        val debugMessages = logger.messages.filter { it.first == "debug" }.map { it.second }
        assertTrue(debugMessages.any { it.contains("[symbol-existence] FAILED") && it.contains("phantomMethod") })
        assertTrue(debugMessages.any { it.contains("refuted 1") })
    }

    @Test
    fun `logs per-finding detail when code snippet check fails`() {
        val logger = CapturingLogger()
        val verifier = AiGroundingVerifier(logger = logger)
        val finding = aiFile("Replace `foo.bar()` with safer alternative")
        val mr = makeMr(fileChange("src/Foo.kt", "+val x = baz.qux()"))

        verifier.verify(listOf(finding), mr)

        val debugMessages = logger.messages.filter { it.first == "debug" }.map { it.second }
        assertTrue(debugMessages.any { it.contains("[code-snippet] FAILED") && it.contains("foo.bar()") })
    }

    @Test
    fun `no per-finding failure logs when all checks pass`() {
        val logger = CapturingLogger()
        val verifier = AiGroundingVerifier(logger = logger)
        val finding = aiFile("Missing `realMethod` call")
        val mr = makeMr(fileChange("src/Foo.kt", "+fun realMethod() {}"))

        verifier.verify(listOf(finding), mr)

        val debugMessages = logger.messages.filter { it.first == "debug" }.map { it.second }
        assertTrue(debugMessages.none { it.contains("FAILED") })
    }

    // ── failureMode ──────────────────────────────────────────────────────

    @Test
    fun `failureMode remove drops refuted finding and keeps grounded one`() {
        val verifier = AiGroundingVerifier(GroundingConfig(failureMode = GroundingConfig.FAILURE_MODE_REMOVE))
        val refuted = aiFile("Missing null check for `phantomMethod`")
        val grounded = aiFile("Missing null check for `realMethod`")
        val mr = makeMr(fileChange("src/Foo.kt", "+fun realMethod() = 42"))

        val result = verifier.verify(listOf(refuted, grounded), mr)

        assertEquals(listOf(grounded.message), result.findings.map { it.message })
        assertEquals("grounded", result.findings.single().metadata[AiFindingMetadataKeys.GROUNDING])
        assertEquals(1, result.refutedCount)
    }

    @Test
    fun `failureMode downgrade wins over autoDowngradeRefuted false`() {
        val verifier = AiGroundingVerifier(
            GroundingConfig(autoDowngradeRefuted = false, failureMode = GroundingConfig.FAILURE_MODE_DOWNGRADE),
        )
        val mr = makeMr(fileChange("src/Foo.kt", "+fun realMethod() = 42"))

        val result = verifier.verify(listOf(aiFile("Missing null check for `phantomMethod`")), mr)

        assertEquals(Severity.INFO, result.findings.single().severity)
    }

    @Test
    fun `autoDowngradeRefuted false keeps a refuted finding while true drops it`() {
        val mr = makeMr(fileChange("src/Foo.kt", "+fun realMethod() = 42"))
        val finding = aiFile("Missing null check for `phantomMethod`")

        val kept = AiGroundingVerifier(GroundingConfig(autoDowngradeRefuted = false)).verify(listOf(finding), mr)
        val dropped = AiGroundingVerifier(GroundingConfig(autoDowngradeRefuted = true)).verify(listOf(finding), mr)

        assertEquals(Severity.WARNING, kept.findings.single().severity)
        assertTrue(
            kept.findings
                .single()
                .message
                .startsWith("[REFUTED: symbol-existence]")
        )
        assertTrue(dropped.findings.isEmpty())
    }

    @Test
    fun `checkCodeSnippets false lets an ungrounded snippet in the explanation pass while true refutes it`() {
        val finding = aiFile(
            message = "Potential issue detected",
            explanation = "The call `service.doThing()` lacks error handling",
        )
        val mr = makeMr(fileChange("src/Foo.kt", "+val x = baz.qux()"))

        val on = AiGroundingVerifier(GroundingConfig(checkCodeSnippets = true, failureMode = GroundingConfig.FAILURE_MODE_DOWNGRADE))
            .verify(listOf(finding), mr)
        val off = AiGroundingVerifier(GroundingConfig(checkCodeSnippets = false)).verify(listOf(finding), mr)

        assertEquals(Severity.INFO, on.findings.single().severity)
        assertTrue(
            on.findings
                .single()
                .message
                .startsWith("[REFUTED: code-snippet]")
        )
        assertEquals(Severity.WARNING, off.findings.single().severity)
        assertEquals(0, off.refutedCount)
    }
}
