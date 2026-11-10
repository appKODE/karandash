package io.karandash.plugins.ai.core.pipeline

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AiMetadataTransformerTest {
    private val aiMeta = mapOf(AiFindingMetadataKeys.AI_MODEL to "m")

    private fun makeFinding(
        rule: String = "code-suggestions",
        metadata: Map<String, String> = emptyMap(),
        message: String = "test message",
        suggestion: String? = null,
    ) = Finding(
        severity = Severity.WARNING,
        message = message,
        rule = rule,
        metadata = metadata,
        suggestion = suggestion,
    )

    @Test
    fun transform_is_idempotent_calling_twice_produces_same_output_as_once() {
        val location = Location.FileRef(path = "src/Idempotent.kt", line = 1)
        val review = makeFinding(rule = "review-comments", metadata = aiMeta, message = "review").copy(location = location)
        val suggestion = makeFinding(
            metadata = mapOf(
                AiFindingMetadataKeys.EXISTING_CODE to "val x = value",
                AiFindingMetadataKeys.SUGGESTED_CODE to "val x = value ?: return",
                AiFindingMetadataKeys.CATEGORY to "bug_risk",
            ),
        ).copy(location = location)

        val once = AiMetadataTransformer.transform(listOf(review, suggestion))
        val twice = AiMetadataTransformer.transform(once)

        assertEquals(1, once.size)
        assertEquals(1, twice.size)
        assertEquals(once[0].suggestion, twice[0].suggestion)
        assertEquals(once[0].message, twice[0].message)
        assertEquals(once[0].metadata, twice[0].metadata)
    }

    @Test
    fun transform_sets_aiSuggestionBaked_sentinel_after_transformation() {
        val location = Location.FileRef(path = "src/Sentinel.kt", line = 5)
        val review = makeFinding(rule = "review-comments", metadata = aiMeta).copy(location = location)
        val suggestion = makeFinding(
            metadata = mapOf(
                AiFindingMetadataKeys.EXISTING_CODE to "val x = value",
                AiFindingMetadataKeys.SUGGESTED_CODE to "val x = value ?: return",
            ),
        ).copy(location = location)

        val result = AiMetadataTransformer.transform(listOf(review, suggestion))

        assertEquals(1, result.size)
        assertNotNull(result[0].metadata[AiFindingMetadataKeys.AI_SUGGESTION_BAKED])
        assertEquals("true", result[0].metadata[AiFindingMetadataKeys.AI_SUGGESTION_BAKED])
    }

    @Test
    fun transform_skips_finding_already_marked_with_sentinel() {
        val alreadyBaked = makeFinding(
            metadata = mapOf(
                AiFindingMetadataKeys.EXISTING_CODE to "val x = value",
                AiFindingMetadataKeys.SUGGESTED_CODE to "val x = value ?: return",
                AiFindingMetadataKeys.AI_SUGGESTION_BAKED to "true",
            ),
            suggestion = "already baked suggestion",
        )

        val result = AiMetadataTransformer.transform(listOf(alreadyBaked))

        assertSame(alreadyBaked, result[0])
        assertEquals("already baked suggestion", result[0].suggestion)
    }

    @Test
    fun transform_diff_block_appears_exactly_once_after_two_calls() {
        val location = Location.FileRef(path = "src/DiffBlock.kt", line = 3)
        val review = makeFinding(rule = "review-comments", metadata = aiMeta).copy(location = location)
        val suggestion = makeFinding(
            metadata = mapOf(
                AiFindingMetadataKeys.EXISTING_CODE to "val x = value",
                AiFindingMetadataKeys.SUGGESTED_CODE to "val x = value ?: return",
                AiFindingMetadataKeys.CATEGORY to "bug_risk",
            ),
        ).copy(location = location)

        val afterOne = AiMetadataTransformer.transform(listOf(review, suggestion))
        val afterTwo = AiMetadataTransformer.transform(afterOne)

        assertEquals(1, afterTwo.size)
        val suggestionText = afterTwo[0].suggestion ?: ""
        val marker = "Suggested change (improvement):"
        val occurrences = suggestionText.split(marker).size - 1
        assertEquals(1, occurrences, "Diff block should appear exactly once even after two transform calls")
    }

    @Test
    fun transform_passes_through_non_ai_findings_unchanged() {
        val finding = makeFinding(rule = "title-format")

        val result = AiMetadataTransformer.transform(listOf(finding))

        assertSame(finding, result[0])
    }

    @Test
    fun mergeSuggestionFindings_matched_pair_produces_single_merged_finding() {
        val location = Location.FileRef(path = "src/Foo.kt", line = 10)
        val review = Finding(
            severity = Severity.WARNING,
            message = "Nullable not handled",
            rule = "review-comments",
            location = location,
            metadata = aiMeta,
        )
        val suggestion = Finding(
            severity = Severity.INFO,
            message = "Suggestion",
            rule = "code-suggestions",
            location = location,
            metadata = mapOf(
                AiFindingMetadataKeys.EXISTING_CODE to "val x = value",
                AiFindingMetadataKeys.SUGGESTED_CODE to "val x = value ?: return",
                AiFindingMetadataKeys.CATEGORY to "bug_risk",
            ),
        )

        val result = AiMetadataTransformer.transform(listOf(review, suggestion))

        assertEquals(1, result.size, "Matched pair should produce exactly one finding")
        val merged = result[0]
        assertEquals("Nullable not handled", merged.message)
        assertEquals("val x = value", merged.metadata[AiFindingMetadataKeys.EXISTING_CODE])
        assertEquals("val x = value ?: return", merged.metadata[AiFindingMetadataKeys.SUGGESTED_CODE])
    }

    @Test
    fun mergeSuggestionFindings_merged_finding_retains_review_severity_not_suggestion_severity() {
        val location = Location.FileRef(path = "src/Count.kt", line = 5)
        val review =
            Finding(severity = Severity.WARNING, message = "review", rule = "review-comments", location = location, metadata = aiMeta)
        val suggestion = Finding(
            severity = Severity.INFO,
            message = "suggestion",
            rule = "code-suggestions",
            location = location,
            metadata = mapOf(
                AiFindingMetadataKeys.EXISTING_CODE to "old",
                AiFindingMetadataKeys.SUGGESTED_CODE to "new",
            ),
        )

        val result = AiMetadataTransformer.transform(listOf(review, suggestion))

        assertEquals(1, result.size)
        assertEquals(Severity.WARNING, result[0].severity, "Merged finding must keep review severity so counts are not doubled")
    }

    @Test
    fun mergeSuggestionFindings_unmatched_suggestion_is_kept() {
        val review = Finding(
            severity = Severity.WARNING,
            message = "Review at line 5",
            rule = "review-comments",
            location = Location.FileRef(path = "src/Foo.kt", line = 5),
            metadata = aiMeta,
        )
        val suggestion = Finding(
            severity = Severity.INFO,
            message = "Suggestion at line 20",
            rule = "code-suggestions",
            location = Location.FileRef(path = "src/Foo.kt", line = 20),
            metadata = mapOf(
                AiFindingMetadataKeys.EXISTING_CODE to "val x = value",
                AiFindingMetadataKeys.SUGGESTED_CODE to "val x = value ?: return",
            ),
        )

        val result = AiMetadataTransformer.transform(listOf(review, suggestion))

        // Separate-mode suggestions sit on their own line; dropping them left that mode with no output.
        assertEquals(listOf("review-comments", "code-suggestions"), result.map { it.rule })
        assertEquals("val x = value ?: return", result[1].metadata[AiFindingMetadataKeys.SUGGESTED_CODE])
    }

    @Test
    fun mergeSuggestionFindings_standalone_review_without_suggestion_kept_unchanged() {
        val review = Finding(
            severity = Severity.WARNING,
            message = "Review finding",
            rule = "review-comments",
            location = Location.FileRef(path = "src/Bar.kt", line = 42),
            metadata = aiMeta,
        )

        val result = AiMetadataTransformer.transform(listOf(review))

        assertEquals(1, result.size)
        assertTrue(result[0].message.contains("Review finding"))
        assertFalse(result[0].metadata.containsKey(AiFindingMetadataKeys.EXISTING_CODE))
    }

    @Test
    fun mergeSuggestionFindings_multiple_pairs_at_different_locations_each_merged() {
        val loc1 = Location.FileRef(path = "src/A.kt", line = 1)
        val loc2 = Location.FileRef(path = "src/B.kt", line = 2)
        val review1 = Finding(
            severity = Severity.WARNING,
            message = "Review 1",
            rule = "review-comments",
            location = loc1,
            metadata = aiMeta + (AiFindingMetadataKeys.CATEGORY to "BUG"),
        )
        val review2 =
            Finding(severity = Severity.WARNING, message = "Review 2", rule = "review-comments", location = loc2, metadata = aiMeta)
        val suggestion1 = Finding(
            severity = Severity.INFO,
            message = "Sug 1",
            rule = "code-suggestions",
            location = loc1,
            metadata = mapOf(
                AiFindingMetadataKeys.EXISTING_CODE to "old1",
                AiFindingMetadataKeys.SUGGESTED_CODE to "new1",
                AiFindingMetadataKeys.CATEGORY to "performance",
            ),
        )
        val suggestion2 = Finding(
            severity = Severity.INFO,
            message = "Sug 2",
            rule = "code-suggestions",
            location = loc2,
            metadata = mapOf(
                AiFindingMetadataKeys.EXISTING_CODE to "old2",
                AiFindingMetadataKeys.SUGGESTED_CODE to "new2",
            ),
        )

        val result = AiMetadataTransformer.transform(listOf(review1, review2, suggestion1, suggestion2))

        assertEquals(2, result.size, "Two pairs should produce two merged findings")
        val merged1 = result.first { it.message == "Review 1" }
        val merged2 = result.first { it.message == "Review 2" }
        assertEquals("old1", merged1.metadata[AiFindingMetadataKeys.EXISTING_CODE])
        assertEquals("old2", merged2.metadata[AiFindingMetadataKeys.EXISTING_CODE])
        // The review keeps its own category: a suggestion's `performance` must not strip a BUG's cap protection.
        assertEquals("BUG", merged1.metadata[AiFindingMetadataKeys.CATEGORY])
        assertTrue(FindingCap.isProtected(merged1))
        assertFalse(merged2.metadata.containsKey(AiFindingMetadataKeys.CATEGORY))
    }

    @Test
    fun mergeSuggestionFindings_already_baked_suggestion_not_re_merged() {
        val location = Location.FileRef(path = "src/C.kt", line = 7)
        val bakedSuggestion = Finding(
            severity = Severity.INFO,
            message = "Already baked",
            rule = "code-suggestions",
            location = location,
            metadata = mapOf(
                AiFindingMetadataKeys.EXISTING_CODE to "old",
                AiFindingMetadataKeys.SUGGESTED_CODE to "new",
                AiFindingMetadataKeys.AI_SUGGESTION_BAKED to "true",
            ),
        )
        val review = Finding(
            severity = Severity.WARNING,
            message = "Review",
            rule = "review-comments",
            location = location,
            metadata = aiMeta,
        )

        val result = AiMetadataTransformer.transform(listOf(review, bakedSuggestion))

        // Baked suggestion is not treated as an unmerged suggestion - it passes through as-is
        assertEquals(2, result.size, "Already-baked suggestion is not consumed by merge pass")
        assertFalse(result.first { it.rule == "review-comments" }.metadata.containsKey(AiFindingMetadataKeys.EXISTING_CODE))
    }

    @Test
    fun multi_line_existing_code_widens_the_location_to_its_last_line() {
        val suggestion = makeFinding(
            metadata = mapOf(
                AiFindingMetadataKeys.EXISTING_CODE to "val a = 1\nval b = 2\nval c = 3\n",
                AiFindingMetadataKeys.SUGGESTED_CODE to "val (a, b, c) = Triple(1, 2, 3)",
            ),
        ).copy(location = Location.FileRef(path = "src/A.kt", line = 7))

        val result = AiMetadataTransformer.transform(listOf(suggestion)).single()

        assertEquals(Location.FileRef(path = "src/A.kt", line = 7, endLine = 9), result.location)
    }

    @Test
    fun single_line_existing_code_keeps_the_single_line_anchor() {
        val location = Location.FileRef(path = "src/A.kt", line = 7)
        val suggestion = makeFinding(
            metadata = mapOf(
                AiFindingMetadataKeys.EXISTING_CODE to "val a = 1",
                AiFindingMetadataKeys.SUGGESTED_CODE to "val a = 2",
            ),
        ).copy(location = location)

        assertEquals(location, AiMetadataTransformer.transform(listOf(suggestion)).single().location)
    }

    private fun aiReview(
        message: String,
        location: Location
    ) = Finding(
        severity = Severity.WARNING,
        message = message,
        rule = "review-comments",
        location = location,
        metadata = mapOf(AiFindingMetadataKeys.AI_MODEL to "m"),
    )

    private fun companion(
        location: Location,
        suggested: String = "val x = value ?: return"
    ) = makeFinding(
        metadata = mapOf(
            AiFindingMetadataKeys.EXISTING_CODE to "val x = value",
            AiFindingMetadataKeys.SUGGESTED_CODE to suggested,
            AiFindingMetadataKeys.AI_MODEL to "m",
        ),
    ).copy(location = location)

    @Test
    fun `a non AI finding at the suggestion line is not paired with it`() {
        val location = Location.FileRef(path = "src/A.kt", line = 3)
        val lint = Finding(severity = Severity.WARNING, message = "lint", rule = "title-format", location = location)
        val suggestion = companion(location)

        val result = AiMetadataTransformer.transform(listOf(lint, suggestion))

        assertEquals(2, result.size)
        assertFalse(result.first { it.rule == "title-format" }.metadata.containsKey(AiFindingMetadataKeys.SUGGESTED_CODE))
    }

    @Test
    fun `an AI review at the suggestion line is still paired with it`() {
        val location = Location.FileRef(path = "src/A.kt", line = 3)

        val result = AiMetadataTransformer.transform(listOf(aiReview("r", location), companion(location)))

        assertEquals(listOf("review-comments"), result.map { it.rule })
    }

    @Test
    fun `a companion suggestion is consumed by one review only`() {
        val location = Location.FileRef(path = "src/A.kt", line = 3)
        val result = AiMetadataTransformer.transform(
            listOf(aiReview("first", location), aiReview("second", location), companion(location)),
        )

        assertEquals(2, result.size)
        assertTrue(result.first { it.message == "first" }.metadata.containsKey(AiFindingMetadataKeys.SUGGESTED_CODE))
        assertFalse(result.first { it.message == "second" }.metadata.containsKey(AiFindingMetadataKeys.SUGGESTED_CODE))
    }

    @Test
    fun `a second companion at the same line is kept as a leftover`() {
        val location = Location.FileRef(path = "src/A.kt", line = 3)
        val result = AiMetadataTransformer.transform(
            listOf(aiReview("r", location), companion(location, "one"), companion(location, "two")),
        )

        assertEquals(listOf("review-comments", "code-suggestions"), result.map { it.rule })
        assertEquals("two", result[1].metadata[AiFindingMetadataKeys.SUGGESTED_CODE])
    }

    @Test
    fun `reportedAt keeps the end line`() {
        val finding = aiReview("r", Location.FileRef(path = "src/A.kt", line = 3, endLine = 5))

        assertEquals(Location.FileRef(path = "src/A.kt", line = 3, endLine = 5), AiMetadataTransformer.reportedAt(finding))
    }

    /** A code suggestion joins the review comment on its line, not an earlier code-quality finding. */
    @Test
    fun `a suggestion pairs with the review comment before another AI finding on its line`() {
        val location = Location.FileRef(path = "src/A.kt", line = 3)
        val quality = aiReview("quality", location).copy(rule = "code-quality")

        val result = AiMetadataTransformer.transform(listOf(quality, aiReview("review", location), companion(location)))

        assertEquals(
            listOf("code-quality" to false, "review-comments" to true),
            result.map {
                it.rule to
                    it.metadata.containsKey(AiFindingMetadataKeys.SUGGESTED_CODE)
            }
        )
    }
}
