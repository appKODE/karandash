@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.cli.pipeline

import io.karandash.api.ModuleRegistryImpl
import io.karandash.cli.TestFileHelpers
import io.karandash.config.Config
import io.karandash.diagnostics.ClientLifecycleDiagnostic
import io.karandash.diagnostics.DiagnosticReport
import io.karandash.engine.ValidationEngine
import io.karandash.engine.ValidationResult
import io.karandash.http.HttpClientLedger
import io.karandash.http.ProviderHttpClientFactory
import io.karandash.logger.NoOpLogger
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.state.RuleExecutionResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** [PipelineDiagnosticBridge]: created only when a path is set, records the run, writes the report. */
class PipelineDiagnosticBridgeTest {
    @Test
    fun noPathMeansNoBridgeAndNoListener() =
        runTest {
            val session = ModuleRegistryImpl().forRun()
            val bridge = PipelineDiagnosticBridge.createIfConfigured(session, null, null, configLoadMs = 1, logger = NoOpLogger)

            assertNull(bridge)
            assertNull(session.services[ProviderHttpClientFactory.DIAGNOSTIC_LISTENER])
        }

    @Test
    fun recordsRulesFindingsAndHttpOnFinalize() =
        runTest {
            val dir = TestFileHelpers.createTempDir("diag-bridge")
            try {
                val out = Path(dir, "diag.json").toString()
                val session = ModuleRegistryImpl().forRun()
                val bridge =
                    assertNotNull(PipelineDiagnosticBridge.createIfConfigured(session, out, null, configLoadMs = 7, logger = NoOpLogger))
                assertNotNull(session.services[ProviderHttpClientFactory.DIAGNOSTIC_LISTENER])

                bridge.http("GET", "https://example.com/mr", statusCode = 200, durationMs = 12)
                bridge.recordValidation(validationStage(), validationDurationMs = 30)
                bridge.finalize(totalMs = 50, logger = NoOpLogger)

                val report = KarandashJson.Default.decodeFromString<DiagnosticReport>(TestFileHelpers.readFile(Path(out)))
                val summary = assertNotNull(report.summary)
                assertEquals(listOf("labels" to "ok", "title" to "ok"), report.rules.map { it.ruleId to it.status })
                assertEquals(3, report.findings.size)
                assertEquals("validation", summary.lastActiveStage)
                assertEquals(7L, summary.stageTimings["configLoad"])
                assertEquals(30L, summary.stageTimings["validation"])
                val rule = summary.ruleTimings.single()
                assertEquals("title", rule.ruleId)
                assertEquals(2, rule.findingCount)
                assertEquals(1, rule.errorCount)
                assertEquals(1, rule.warningCount)
                assertEquals(4L, rule.durationMs)
                val findings = assertNotNull(summary.findingSummary)
                assertEquals(3, findings.total)
                assertEquals(1, findings.errors)
                assertEquals(1, findings.infos)
                assertEquals(false, findings.passed)
                assertEquals("https://example.com/mr", summary.httpRequests.single().url)
            } finally {
                TestFileHelpers.deleteTempDir(dir)
            }
        }

    @Test
    fun countsBothCreatedAndClosedHttpClients() =
        runTest {
            val dir = TestFileHelpers.createTempDir("diag-bridge-clients")
            try {
                val out = Path(dir, "diag.json").toString()
                val session = ModuleRegistryImpl().forRun()
                session.provide(ProviderHttpClientFactory.CLIENT_LEDGER, HttpClientLedger())
                val bridge =
                    assertNotNull(PipelineDiagnosticBridge.createIfConfigured(session, out, null, configLoadMs = 0, logger = NoOpLogger))

                ProviderHttpClientFactory.create(engine = MockEngine { respondOk() }, services = session.services).close()
                bridge.finalize(totalMs = 0, logger = NoOpLogger)

                val summary = assertNotNull(
                    KarandashJson.Default.decodeFromString<DiagnosticReport>(TestFileHelpers.readFile(Path(out))).summary,
                )
                assertEquals(ClientLifecycleDiagnostic(clientCreates = 1, clientCloses = 1), summary.clientLifecycle)
            } finally {
                TestFileHelpers.deleteTempDir(dir)
            }
        }

    @Test
    fun findingSummaryCountsTheReportedFindings() =
        runTest {
            val dir = TestFileHelpers.createTempDir("diag-bridge-reported")
            try {
                val out = Path(dir, "diag.json").toString()
                val session = ModuleRegistryImpl().forRun()
                val bridge =
                    assertNotNull(PipelineDiagnosticBridge.createIfConfigured(session, out, null, configLoadMs = 1, logger = NoOpLogger))
                val reported = validationStage().result.findings +
                    Finding(severity = Severity.WARNING, message = "digest", rule = "ai") +
                    Finding(severity = Severity.INFO, message = "cached", rule = "ai")

                bridge.recordValidation(validationStage(), validationDurationMs = 30)
                bridge.recordFindingSummary(reported, passed = false)
                bridge.finalize(totalMs = 50, logger = NoOpLogger)

                val report = KarandashJson.Default.decodeFromString<DiagnosticReport>(TestFileHelpers.readFile(Path(out)))
                val findings = assertNotNull(assertNotNull(report.summary).findingSummary)
                assertEquals(5, findings.total)
                assertEquals(2, findings.warnings)
                assertEquals(2, findings.infos)
            } finally {
                TestFileHelpers.deleteTempDir(dir)
            }
        }

    private fun validationStage(): ValidationStage {
        val findings = listOf(
            Finding(severity = Severity.ERROR, message = "bad", rule = "title"),
            Finding(severity = Severity.WARNING, message = "meh", rule = "title"),
            Finding(severity = Severity.INFO, message = "fyi", rule = "labels"),
        )
        val result = ValidationResult(
            findings = findings,
            ruleExecutionResults = listOf(
                RuleExecutionResult(
                    ruleId = "title",
                    findings = findings.take(2),
                    fromCache = false,
                    executionDurationNs = 4_000_000,
                ),
            ),
        )
        return ValidationStage(engine = ValidationEngine(Config(), ModuleRegistryImpl()), result = result)
    }
}
