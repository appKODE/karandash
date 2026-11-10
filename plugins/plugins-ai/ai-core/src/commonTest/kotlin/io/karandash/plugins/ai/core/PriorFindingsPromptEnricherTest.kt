package io.karandash.plugins.ai.core

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.config.AiPriorFindingsConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PriorFindingsPromptEnricherTest {
    private fun finding(
        rule: String,
        path: String? = null,
        line: Int? = null,
        sha: String? = null,
        severity: Severity = Severity.WARNING,
        message: String = "An issue",
        confidence: Float? = null,
    ): Finding {
        val location = when {
            path != null -> Location.FileRef(path = path, line = line)
            sha != null -> Location.CommitRef(sha = sha)
            else -> null
        }
        return Finding(
            severity = severity,
            message = message,
            rule = rule,
            location = location,
            confidence = confidence,
        )
    }

    @Test
    fun `returns null when feature is disabled`() {
        val enricher = PriorFindingsPromptEnricher(
            priorByFile = mapOf("src/Foo.kt" to listOf(finding("review-comments", path = "src/Foo.kt"))),
            priorMrLevel = emptyList(),
            config = AiPriorFindingsConfig(enabled = false),
        )
        assertNull(enricher.enrichPrompt("review-comments"))
    }

    @Test
    fun `returns null when both prior inputs are empty`() {
        val enricher = PriorFindingsPromptEnricher(
            priorByFile = emptyMap(),
            priorMrLevel = emptyList(),
            config = AiPriorFindingsConfig(),
        )
        assertNull(enricher.enrichPrompt("review-comments"))
    }

    @Test
    fun `returns null when no prior finding matches the rule id`() {
        val enricher = PriorFindingsPromptEnricher(
            priorByFile = mapOf(
                "src/Foo.kt" to listOf(finding("code-suggestions", path = "src/Foo.kt")),
            ),
            priorMrLevel = listOf(finding("mr-summary", sha = "abc123")),
            config = AiPriorFindingsConfig(),
        )
        assertNull(enricher.enrichPrompt("review-comments"))
    }

    @Test
    fun `renders per-file section with matching rule findings only`() {
        val enricher = PriorFindingsPromptEnricher(
            priorByFile = mapOf(
                "src/Foo.kt" to listOf(
                    finding("review-comments", path = "src/Foo.kt", line = 10, message = "Missing null check"),
                    finding("code-suggestions", path = "src/Foo.kt", line = 99, message = "Not our rule"),
                ),
                "src/Bar.kt" to listOf(
                    finding("review-comments", path = "src/Bar.kt", line = 42, message = "Magic number"),
                ),
            ),
            priorMrLevel = emptyList(),
            config = AiPriorFindingsConfig(),
        )

        val text = enricher.enrichPrompt("review-comments")
        checkNotNull(text)
        assertTrue(text.contains("## Previously Reported Findings"))
        assertTrue(text.contains("### src/Foo.kt"))
        assertTrue(text.contains("line 10 [WARNING]: Missing null check"))
        assertTrue(text.contains("### src/Bar.kt"))
        assertTrue(text.contains("line 42 [WARNING]: Magic number"))
        // Other rule's finding must not leak in.
        assertFalse(text.contains("Not our rule"))
    }

    @Test
    fun `renders MR-level section with commit location`() {
        val enricher = PriorFindingsPromptEnricher(
            priorByFile = emptyMap(),
            priorMrLevel = listOf(
                finding("mr-summary", sha = "abcdef1234567890", severity = Severity.INFO, message = "Prior summary"),
            ),
            config = AiPriorFindingsConfig(),
        )

        val text = enricher.enrichPrompt("mr-summary")
        checkNotNull(text)
        assertTrue(text.contains("### MR-level"))
        assertTrue(text.contains("commit abcdef1 [INFO]: Prior summary"))
    }

    @Test
    fun `maxPerFile caps list and appends omission notice`() {
        val enricher = PriorFindingsPromptEnricher(
            priorByFile = mapOf(
                "src/Foo.kt" to listOf(
                    finding("review-comments", path = "src/Foo.kt", line = 1, severity = Severity.ERROR, message = "E1"),
                    finding("review-comments", path = "src/Foo.kt", line = 2, severity = Severity.WARNING, message = "W1"),
                    finding("review-comments", path = "src/Foo.kt", line = 3, severity = Severity.WARNING, message = "W2"),
                    finding("review-comments", path = "src/Foo.kt", line = 4, severity = Severity.INFO, message = "I1"),
                ),
            ),
            priorMrLevel = emptyList(),
            config = AiPriorFindingsConfig(maxPerFile = 2),
        )

        val text = enricher.enrichPrompt("review-comments")
        checkNotNull(text)
        // Higher severity ordered first: ERROR then WARNING. INFO and W2 should drop.
        assertTrue(text.contains("line 1 [ERROR]: E1"))
        assertTrue(text.contains("and 2 more finding(s)"))
        assertFalse(text.contains("I1"))
    }

    @Test
    fun `maxForMrLevel caps MR-level section independently`() {
        val enricher = PriorFindingsPromptEnricher(
            priorByFile = emptyMap(),
            priorMrLevel = listOf(
                finding("risk-scoring", sha = "aaaaaaa", severity = Severity.ERROR, message = "Critical risk"),
                finding("risk-scoring", sha = "bbbbbbb", severity = Severity.WARNING, message = "Medium risk"),
            ),
            config = AiPriorFindingsConfig(maxForMrLevel = 1),
        )

        val text = enricher.enrichPrompt("risk-scoring")
        checkNotNull(text)
        assertTrue(text.contains("Critical risk"))
        assertFalse(text.contains("Medium risk"))
        assertTrue(text.contains("and 1 more finding(s)"))
    }

    @Test
    fun `instruction block says already raised or rejected do not repeat`() {
        val enricher = PriorFindingsPromptEnricher(
            priorByFile = mapOf(
                "src/Foo.kt" to listOf(finding("review-comments", path = "src/Foo.kt", line = 1)),
            ),
            priorMrLevel = emptyList(),
            config = AiPriorFindingsConfig(),
        )
        val text = enricher.enrichPrompt("review-comments")
        checkNotNull(text)
        assertTrue(text.contains("## Previously Reported Findings (already raised or rejected, do not repeat)"), text)
        assertTrue(text.contains("Do NOT repeat or paraphrase them"), text)
        assertTrue(text.contains("Report ONLY new issues"), text)
        assertFalse(text.contains("repeating is fine"), text)
        assertFalse(text.contains("INCLUDE it"), text)
    }

    @Test
    fun `drops empty per-file entries before rendering`() {
        val enricher = PriorFindingsPromptEnricher(
            priorByFile = mapOf(
                "src/Foo.kt" to emptyList(),
                "src/Bar.kt" to listOf(finding("review-comments", path = "src/Bar.kt")),
            ),
            priorMrLevel = emptyList(),
            config = AiPriorFindingsConfig(),
        )
        val text = enricher.enrichPrompt("review-comments")
        checkNotNull(text)
        assertFalse(text.contains("### src/Foo.kt"))
        assertTrue(text.contains("### src/Bar.kt"))
    }

    @Test
    fun `config rejects non-positive caps`() {
        val ex1 = runCatching { AiPriorFindingsConfig(maxPerFile = 0) }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class, ex1?.let { it::class })
        val ex2 = runCatching { AiPriorFindingsConfig(maxForMrLevel = -5) }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class, ex2?.let { it::class })
    }

    private fun dismissedOnly(
        store: FeedbackStore,
        maxDismissed: Int = PriorFindingsPromptEnricher.DEFAULT_MAX_DISMISSED,
    ) = PriorFindingsPromptEnricher(
        priorByFile = emptyMap(),
        priorMrLevel = emptyList(),
        config = AiPriorFindingsConfig(),
        feedbackStore = store,
        maxDismissed = maxDismissed,
    )

    private fun storeWith(vararg entries: Triple<String, String?, String>): FeedbackStore {
        val store = FeedbackStore()
        for ((rule, filePath, message) in entries) {
            store.dismiss(rule, filePath, message.hashCode(), message)
        }
        return store
    }

    @Test
    fun `a dismissed prior finding is dropped and a live one survives`() {
        val store = storeWith(Triple("review-comments", "src/Foo.kt", "Dismissed by the reviewer"))
        val enricher = PriorFindingsPromptEnricher(
            priorByFile = mapOf(
                "src/Foo.kt" to listOf(
                    finding("review-comments", path = "src/Foo.kt", message = "Dismissed by the reviewer"),
                    finding("review-comments", path = "src/Foo.kt", message = "Still open upstream"),
                ),
            ),
            priorMrLevel = emptyList(),
            config = AiPriorFindingsConfig(),
            feedbackStore = store,
        )
        val text = checkNotNull(enricher.enrichPrompt("review-comments"))
        assertFalse(text.contains("Dismissed by the reviewer"), text)
        assertTrue(text.contains("Still open upstream"), text)
    }

    @Test
    fun `empty feedback store renders no dismissed section`() {
        assertNull(dismissedOnly(FeedbackStore()).dismissedPrompt("ai-review"))
    }

    @Test
    fun `dismissed entry for the rule renders the do-not-repeat section`() {
        val store = storeWith(Triple("ai-review", "src/Main.kt", "Consider adding null checks here"))
        val prompt = checkNotNull(dismissedOnly(store).dismissedPrompt("ai-review"))
        assertTrue(prompt.contains("Previously Dismissed Findings"))
        assertTrue(prompt.contains("Consider adding null checks here"))
        assertTrue(prompt.contains("ai-review"))
        assertTrue(prompt.contains("src/Main.kt"))
    }

    @Test
    fun `dismissed section is filtered to the rule`() {
        val store = storeWith(
            Triple("ai-review", "src/A.kt", "Add error handling"),
            Triple("ai-code-quality", "src/B.kt", "Simplify this block"),
        )
        val enricher = dismissedOnly(store)
        val review = checkNotNull(enricher.dismissedPrompt("ai-review"))
        assertTrue(review.contains("Add error handling"))
        assertFalse(review.contains("Simplify this block"))
        assertTrue(checkNotNull(enricher.dismissedPrompt("ai-code-quality")).contains("Simplify this block"))
        assertNull(enricher.dismissedPrompt("other-rule"))
    }

    @Test
    fun `dismissed section is capped at maxDismissed examples`() {
        val store = FeedbackStore()
        repeat(10) { i -> store.dismiss("ai-review", "src/File$i.kt", i, "Dismissed message $i") }
        val prompt = checkNotNull(dismissedOnly(store, maxDismissed = 3).dismissedPrompt("ai-review"))
        assertTrue(prompt.contains("3."))
        assertFalse(prompt.contains("4."))
    }

    @Test
    fun `dismissed section shows the dismiss count when above one`() {
        val store = FeedbackStore()
        val hash = "Repeated dismissal".hashCode()
        repeat(3) { store.dismiss("ai-review", null, hash, "Repeated dismissal") }
        assertTrue(checkNotNull(dismissedOnly(store).dismissedPrompt("ai-review")).contains("dismissed 3 times"))
    }
}
