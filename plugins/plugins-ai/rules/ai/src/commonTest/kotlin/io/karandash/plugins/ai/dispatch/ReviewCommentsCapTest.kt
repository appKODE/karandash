package io.karandash.plugins.ai.dispatch

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.AiRuleIds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewCommentsCapTest {
    private fun finding(
        severity: Severity,
        message: String,
        rule: String,
        location: Location.FileRef,
    ) = Finding(severity, message, rule, location, metadata = mapOf(AiFindingMetadataKeys.AI_MODEL to "m"))

    @Test
    fun `a multi-line code-suggestion twin follows its comment through the cap and is not counted`() =
        runTest {
            val comment = finding(Severity.ERROR, "Null deref", AiRuleIds.REVIEW_COMMENTS, Location.FileRef("src/A.kt", 5))
            val other = finding(Severity.INFO, "Naming", AiRuleIds.REVIEW_COMMENTS, Location.FileRef("src/A.kt", 9))
            val twin = finding(Severity.INFO, "Guard it", AiRuleIds.CODE_SUGGESTIONS, Location.FileRef("src/A.kt", 5, endLine = 7))
            val otherTwin = finding(Severity.INFO, "Rename", AiRuleIds.CODE_SUGGESTIONS, Location.FileRef("src/A.kt", 9, endLine = 10))

            val kept = ReviewCommentsCap.apply(
                AiRuleIds.REVIEW_COMMENTS,
                JsonObject(mapOf("maxComments" to JsonPrimitive(1))),
                listOf(comment, other, twin, otherTwin),
            )

            assertEquals(listOf("Null deref", "Guard it"), kept.map { it.message })
        }
}
