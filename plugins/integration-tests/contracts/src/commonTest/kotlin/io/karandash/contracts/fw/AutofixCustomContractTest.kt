package io.karandash.contracts.fw

import io.karandash.contracts.support.MarkerRecordingPlatformService
import io.karandash.fixing.AutoFixer
import io.karandash.fixing.FixType
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FixSuggestion
import io.karandash.models.finding.Severity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `--fix` silently drops `FixSuggestion.Custom`. `AutoFixer.toFixAction` maps it to
 * `FixType.CUSTOM`, but `applyFixes` has no CUSTOM branch, so the action is listed as fixable and then
 * gets no [io.karandash.fixing.FixResult] at all. The user is never told the fix was not applied.
 */
class AutofixCustomContractTest {
    @Test
    fun `applyFixes reports a result for every custom fix action`() =
        runTest {
            val fixer = AutoFixer(MarkerRecordingPlatformService(), projectId = "group/proj", mrId = "7")
            val finding = Finding(
                severity = Severity.ERROR,
                message = "Missing changelog entry",
                rule = "changelog",
                fixSuggestions = listOf(FixSuggestion.Custom(type = "add-changelog", description = "Add changelog")),
            )
            val actions = fixer.analyzeFixable(listOf(finding))
            assertEquals(listOf(FixType.CUSTOM), actions.map { it.type }, "custom suggestion not analyzed as fixable")

            val results = fixer.applyFixes(actions)

            assertEquals(
                actions,
                results.map { it.action },
                "applyFixes returned no FixResult for the CUSTOM action, it is silently dropped",
            )
        }
}
