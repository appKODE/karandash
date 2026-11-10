package io.karandash.validators

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class MultiValidatorPerformanceTest {
    @Test
    fun testMultipleValidatorsProduceConsistentResultsOnRepeatedRuns() {
        val titleValidator = TitleFormatValidator(
            TitleFormatConfig(pattern = "^(feat|fix|chore): .+")
        )
        val branchValidator = BranchNamingValidator(
            BranchNamingConfig(
                pattern = "feature/.*",
                requireSourceMatch = true,
                requireTargetMatch = false
            )
        )
        val securityValidator = SecurityValidator(
            SecurityConfig(
                scanForSecrets = false,
                secretPatterns = emptyList(),
                sensitiveFiles = listOf("**/*.pem")
            )
        )

        val mr = createTestMergeRequest(
            title = "feat: improve performance",
            sourceBranch = "feature/perf-improvements",
            changes = listOf(FileChange("src/main.kt", ChangeType.MODIFIED))
        )

        val titleBaseline = titleValidator.validate(mr)
        val branchBaseline = branchValidator.validate(mr)
        val securityBaseline = securityValidator.validate(mr)

        repeat(10) { iteration ->
            val titleFindings = titleValidator.validate(mr)
            val branchFindings = branchValidator.validate(mr)
            val securityFindings = securityValidator.validate(mr)

            assertEquals(
                titleBaseline.size,
                titleFindings.size,
                "TitleFormatValidator finding count changed on iteration $iteration"
            )
            assertEquals(
                branchBaseline.size,
                branchFindings.size,
                "BranchNamingValidator finding count changed on iteration $iteration"
            )
            assertEquals(
                securityBaseline.size,
                securityFindings.size,
                "SecurityValidator finding count changed on iteration $iteration"
            )

            titleBaseline.zip(titleFindings).forEachIndexed { idx, (expected, actual) ->
                assertEquals(
                    expected.rule,
                    actual.rule,
                    "TitleFormatValidator rule mismatch at finding[$idx] on iteration $iteration"
                )
                assertEquals(
                    expected.severity,
                    actual.severity,
                    "TitleFormatValidator severity mismatch at finding[$idx] on iteration $iteration"
                )
            }

            branchBaseline.zip(branchFindings).forEachIndexed { idx, (expected, actual) ->
                assertEquals(
                    expected.rule,
                    actual.rule,
                    "BranchNamingValidator rule mismatch at finding[$idx] on iteration $iteration"
                )
                assertEquals(
                    expected.severity,
                    actual.severity,
                    "BranchNamingValidator severity mismatch at finding[$idx] on iteration $iteration"
                )
            }

            securityBaseline.zip(securityFindings).forEachIndexed { idx, (expected, actual) ->
                assertEquals(
                    expected.rule,
                    actual.rule,
                    "SecurityValidator rule mismatch at finding[$idx] on iteration $iteration"
                )
                assertEquals(
                    expected.severity,
                    actual.severity,
                    "SecurityValidator severity mismatch at finding[$idx] on iteration $iteration"
                )
            }
        }
    }
}
