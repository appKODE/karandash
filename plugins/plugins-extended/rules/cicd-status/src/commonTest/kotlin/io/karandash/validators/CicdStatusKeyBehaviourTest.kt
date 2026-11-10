package io.karandash.validators

import io.karandash.models.CiJobStatus
import io.karandash.models.finding.Severity
import io.karandash.sdk.config.BlockMode
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CicdStatusKeyBehaviourTest {
    private fun mr(
        status: String?,
        jobs: List<CiJobStatus> = emptyList(),
    ) = createTestMergeRequest().copy(ciStatus = status, ciJobs = jobs)

    @Test
    fun `requirePassed flags a pipeline that is not passing`() {
        assertEquals(1, CicdStatusValidator(CicdStatusConfig(requirePassed = true)).validate(mr("running")).size)
        assertEquals(emptyList(), CicdStatusValidator(CicdStatusConfig(requirePassed = false)).validate(mr("running")))
    }

    @Test
    fun `blockOnFailing decides between error and warning`() {
        val blocking = CicdStatusValidator(CicdStatusConfig(blockOnFailing = BlockMode.FAIL)).validate(mr("failed"))
        val relaxed = CicdStatusValidator(CicdStatusConfig(blockOnFailing = BlockMode.WARN)).validate(mr("failed"))
        assertEquals(Severity.ERROR, blocking.single().severity)
        assertEquals(Severity.WARNING, relaxed.single().severity)
    }

    @Test
    fun `requiredJobs flags a missing or failing job`() {
        val config = CicdStatusConfig(requiredJobs = listOf("build"))
        val validator = CicdStatusValidator(config)
        val failing = validator.validate(mr("success", listOf(CiJobStatus("build", "failed"))))
        assertTrue(failing.single().message.contains("build"))
        assertTrue(
            validator
                .validate(mr("success", listOf(CiJobStatus("lint", "success"))))
                .single()
                .message
                .contains("not found")
        )
        assertEquals(emptyList(), validator.validate(mr("success", listOf(CiJobStatus("build", "success")))))
    }
}
