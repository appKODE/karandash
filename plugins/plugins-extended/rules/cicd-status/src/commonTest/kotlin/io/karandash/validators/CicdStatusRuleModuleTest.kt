package io.karandash.validators

import io.karandash.api.platform.MergeRequestField
import io.karandash.models.CiJobStatus
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CicdStatusRuleModuleTest {
    private val module = CicdStatusRuleModule()

    private fun config(
        requirePassed: Boolean = true,
        blockOnFailing: Boolean = true,
        jobs: List<String> = emptyList(),
    ): JsonElement =
        JsonObject(
            mapOf(
                "requirePassed" to JsonPrimitive(requirePassed),
                "blockOnFailing" to JsonPrimitive(blockOnFailing),
                "requiredJobs" to JsonArray(jobs.map { JsonPrimitive(it) }),
            ),
        )

    private suspend fun validate(
        status: String?,
        config: JsonElement = JsonObject(emptyMap()),
        jobs: List<CiJobStatus> = emptyList(),
    ) = module.validate(
        CicdStatusRuleModule.RULE_ID,
        createTestMergeRequest().copy(ciStatus = status, ciJobs = jobs),
        config,
    )

    @Test
    fun `module is opt-in and needs ci status`() =
        runTest {
            assertFalse(module.defaultEnabled)
            assertEquals("io.karandash.rule.cicd-status", module.id)
            assertEquals(setOf(MergeRequestField.CI_STATUS), module.requiredMergeRequestFields("cicdStatus"))
            assertNotNull(module.ruleExplanation("cicdStatus"))
        }

    @Test
    fun `passing statuses are accepted case-insensitively and trimmed`() =
        runTest {
            listOf("success", " PASSED ", "Ok", "succeeded", "pass").forEach {
                assertTrue(validate(it).isEmpty(), it)
            }
        }

    @Test
    fun `failing pipeline with defaults is an error naming the raw status`() =
        runTest {
            val finding = validate("Failed").single()

            assertEquals(Severity.ERROR, finding.severity)
            assertEquals("CI/CD pipeline is not passing (status: Failed)", finding.message)
            assertEquals("Check the pipeline logs and fix failing jobs.", finding.suggestion)
        }

    @Test
    fun `non-blocking config downgrades failures to warnings`() =
        runTest {
            val findings = validate("running", config(blockOnFailing = false, jobs = listOf("lint")), listOf(CiJobStatus("lint", "failed")))

            assertEquals(listOf(Severity.WARNING, Severity.WARNING), findings.map { it.severity })
            assertEquals("Required CI/CD job 'lint' is not passing (status: failed)", findings[1].message)
        }

    @Test
    fun `missing or blank status warns that it cannot verify`() =
        runTest {
            listOf(null, "  ").forEach { status ->
                val finding = validate(status).single()
                assertEquals(Severity.WARNING, finding.severity)
                assertEquals("CI/CD status is unavailable; unable to verify required passing pipeline", finding.message)
            }
        }

    @Test
    fun `without require passed only failing states block`() =
        runTest {
            val cfg = config(requirePassed = false)

            assertEquals("CI/CD pipeline is failing (status: timed_out)", validate("timed_out", cfg).single().message)
            assertTrue(validate("running", cfg).isEmpty(), "pending pipelines are not failures")
            assertTrue(validate(null, cfg).isEmpty())
            assertTrue(validate("failed", config(requirePassed = false, blockOnFailing = false)).isEmpty())
        }

    @Test
    fun `required jobs without job data warns listing the jobs`() =
        runTest {
            val finding = validate("success", config(jobs = listOf("build", "test"))).single()

            assertEquals(Severity.WARNING, finding.severity)
            assertEquals("CI/CD job-level status is unavailable; unable to verify required jobs: build, test", finding.message)
        }

    @Test
    fun `required jobs match names case-insensitively and report missing ones`() =
        runTest {
            val jobs = listOf(CiJobStatus("Build", "SUCCESS"), CiJobStatus("test", ""))

            val findings = validate("success", config(jobs = listOf("build", "test", "deploy")), jobs)

            assertEquals(
                listOf(
                    "Required CI/CD job 'test' is not passing (status: )",
                    "Required CI/CD job not found: deploy",
                ),
                findings.map { it.message },
            )
            assertTrue(findings.all { it.severity == Severity.ERROR })
        }
}
