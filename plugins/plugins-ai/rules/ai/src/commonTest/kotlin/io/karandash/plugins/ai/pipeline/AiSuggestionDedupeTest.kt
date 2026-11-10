package io.karandash.plugins.ai.pipeline

import io.karandash.logger.NoOpLogger
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** Code suggestions are deduped against review-comments by location, with or without `ai.filter`. */
class AiSuggestionDedupeTest {
    private val processor = AiFindingPostProcessor(NoOpLogger)

    private fun finding(
        rule: String,
        line: Int,
    ) = Finding(severity = Severity.INFO, message = "$rule at $line", rule = rule, location = Location.FileRef("A.kt", line))

    private val findings = listOf(
        finding(AiRuleIds.REVIEW_COMMENTS, 5),
        finding(AiRuleIds.CODE_SUGGESTIONS, 5),
        finding(AiRuleIds.CODE_SUGGESTIONS, 9),
        finding(AiRuleIds.CODE_SUGGESTIONS, 9),
    )

    @Test
    fun `a code suggestion at a review comment location is dropped without filter config`() =
        runTest {
            val kept = processor.applyFilter(Json.parseToJsonElement("{}"), findings, createTestMergeRequest())

            assertEquals(
                listOf(AiRuleIds.REVIEW_COMMENTS to 5, AiRuleIds.CODE_SUGGESTIONS to 9),
                kept.map { it.rule to (it.location as Location.FileRef).line },
            )
        }

    @Test
    fun `file-level code suggestions on one path stay apart unless the message repeats`() =
        runTest {
            fun fileLevel(message: String) =
                Finding(severity = Severity.INFO, message = message, rule = AiRuleIds.CODE_SUGGESTIONS, location = Location.FileRef("A.kt"))
            val input = listOf(fileLevel("split this file"), fileLevel("add a module doc"), fileLevel("split this file"))

            val kept = processor.applyFilter(Json.parseToJsonElement("{}"), input, createTestMergeRequest())

            assertEquals(listOf("split this file", "add a module doc"), kept.map { it.message })
        }

    @Test
    fun `a committable suggestion stays when the review comment on its line has no code`() =
        runTest {
            val withCode = finding(AiRuleIds.CODE_SUGGESTIONS, 5).copy(metadata = mapOf(AiFindingMetadataKeys.SUGGESTED_CODE to "fixed()"))
            val commentWithCode = finding(
                AiRuleIds.REVIEW_COMMENTS,
                7
            ).copy(metadata = mapOf(AiFindingMetadataKeys.SUGGESTED_CODE to "x()"))
            val input =
                listOf(
                    finding(AiRuleIds.REVIEW_COMMENTS, 5),
                    withCode,
                    commentWithCode,
                    withCode.copy(location = Location.FileRef("A.kt", 7))
                )

            val kept = processor.applyFilter(Json.parseToJsonElement("{}"), input, createTestMergeRequest())

            assertEquals(
                listOf(AiRuleIds.REVIEW_COMMENTS to 5, AiRuleIds.REVIEW_COMMENTS to 7, AiRuleIds.CODE_SUGGESTIONS to 5),
                kept.map { it.rule to (it.location as Location.FileRef).line },
            )
        }
}
