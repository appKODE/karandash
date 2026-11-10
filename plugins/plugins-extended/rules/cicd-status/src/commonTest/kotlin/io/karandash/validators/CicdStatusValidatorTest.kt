package io.karandash.validators

import io.karandash.models.CiJobStatus
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Severity
import io.karandash.sdk.config.BlockMode
import kotlin.test.Test
import kotlin.test.assertTrue

class CicdStatusValidatorTest {
    private fun createMockMR(
        ciStatus: String? = null,
        ciJobs: List<CiJobStatus> = emptyList()
    ): MergeRequest =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "Test MR",
            description = "Test description",
            sourceBranch = "feature/test",
            targetBranch = "main",
            author = User(username = "testuser", name = "Test User"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = emptyList(),
            ciStatus = ciStatus,
            ciJobs = ciJobs
        )

    @Test
    fun testRequirePassedSucceedsWhenPipelinePassed() {
        val config = CicdStatusConfig(requirePassed = true)
        val validator = CicdStatusValidator(config)
        val findings = validator.validate(createMockMR(ciStatus = "success"))

        assertTrue(findings.isEmpty())
    }

    @Test
    fun testRequirePassedFailsWhenPipelineFailed() {
        val config = CicdStatusConfig(requirePassed = true, blockOnFailing = BlockMode.FAIL)
        val validator = CicdStatusValidator(config)
        val findings = validator.validate(createMockMR(ciStatus = "failed"))

        assertTrue(findings.isNotEmpty(), "Should produce findings")
        assertTrue(findings.any { it.rule == "cicdStatus" }, "Should have cicd-status rule")
        assertTrue(findings.any { it.severity == Severity.ERROR }, "Should block on failure")
        assertTrue(findings.any { it.message.contains("not passing") || it.message.contains("failing") })
    }

    @Test
    fun testRequiredJobsProducesFindingWhenJobIsMissingOrFailing() {
        val config = CicdStatusConfig(
            requirePassed = false,
            requiredJobs = listOf("build", "test", "lint")
        )
        val validator = CicdStatusValidator(config)
        val findings = validator.validate(
            createMockMR(
                ciStatus = "success",
                ciJobs = listOf(
                    CiJobStatus(name = "build", status = "success"),
                    CiJobStatus(name = "test", status = "failed")
                )
            )
        )

        assertTrue(findings.isNotEmpty(), "Should produce findings")
        assertTrue(findings.any { it.message.contains("test") }, "Should mention required jobs")
        assertTrue(findings.any { it.message.contains("lint") }, "Should mention required jobs")
    }

    @Test
    fun testRequiredJobsPassWhenAllRequiredJobsAreSuccessful() {
        val config = CicdStatusConfig(
            requirePassed = false,
            requiredJobs = listOf("build", "test"),
            blockOnFailing = BlockMode.FAIL
        )
        val validator = CicdStatusValidator(config)
        val findings = validator.validate(
            createMockMR(
                ciStatus = "success",
                ciJobs = listOf(
                    CiJobStatus(name = "build", status = "success"),
                    CiJobStatus(name = "test", status = "passed")
                )
            )
        )

        assertTrue(findings.isEmpty())
    }

    @Test
    fun testDisabledValidationProducesNoFindings() {
        val config = CicdStatusConfig(
            requirePassed = false,
            requiredJobs = emptyList(),
            blockOnFailing = BlockMode.WARN
        )
        val validator = CicdStatusValidator(config)
        val findings = validator.validate(createMockMR())

        assertTrue(findings.isEmpty())
    }
}
