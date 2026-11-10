package io.karandash.cli.pipeline

import io.karandash.api.PluginContext
import io.karandash.api.finding.AnalyzerProvenance
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.ProvenanceStatus
import io.karandash.api.finding.ReviewProvenance
import io.karandash.api.finding.ValidationReport
import io.karandash.api.module.FormatModule
import io.karandash.api.module.OutputModule
import io.karandash.api.module.PlatformServiceModule
import io.karandash.cli.commands.validate.ResolvedFormatOutput
import io.karandash.helpers.output.NotificationState
import io.karandash.models.DiffRefs
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.testfixtures.createTestReport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The outputs' `when` key: which deliveries a run makes, against the state the MR summary comment carries. */
class NotificationGateTest {
    private val failing = createTestReport()
    private val passing = createTestReport(findings = emptyList())

    @Test
    fun `when changed holds back an output whose outcome matches the summary comment`() =
        runTest {
            val pairs = listOf(pair("slack", "changed"), pair("email", "changed"))
            assertEquals(emptyList(), gate(pairs, failing, FakePlatform(marker(failing))))
            assertEquals(listOf("slack", "email"), ids(gate(pairs, failing, FakePlatform(marker(passing)))))
        }

    @Test
    fun `when changed delivers on a local run and when nothing was posted before`() =
        runTest {
            val pairs = listOf(pair("email", "changed"))
            assertEquals(listOf("email"), ids(gate(pairs, failing, platform = null)))
            assertEquals(listOf("email"), ids(gate(pairs, failing, FakePlatform(summary = null))))
        }

    @Test
    fun `when changed without a marker delivers on every run and warns once`() =
        runTest {
            val logger = RecordingLogger()
            val gate = NotificationGate(logger)
            val pairs = listOf(pair("slack", "changed"))
            repeat(2) { assertEquals(listOf("slack"), ids(gate.apply(pairs, failing, FakePlatform(summary = "Summary"), "p", "1"))) }
            assertEquals(1, logger.warnings.count { "No summary state marker" in it }, logger.warnings.toString())
        }

    @Test
    fun `a platform output gets the summary state the gate read so it skips its own read`() =
        runTest {
            val platform = FakePlatform(marker(passing))
            val pairs = listOf(pair("platform", null, platform = true), pair("slack", "changed"))
            val out = gate(pairs, failing, platform)
            val previous = assertNotNull((out[0].outputConfig as JsonObject)["previousNotificationState"]) as JsonObject
            assertEquals(JsonPrimitive(true), previous["passed"])
            assertEquals(1, platform.reads)
            val first = gate(pairs.take(1), failing, FakePlatform(summary = null)).single()
            assertEquals(null, (first.outputConfig as JsonObject)["previousNotificationState"])
        }

    @Test
    fun `when failed always and never follow the verdict`() =
        runTest {
            val pairs = listOf(pair("slack", "failed"), pair("webhook", "always"), pair("email", "never"))
            assertEquals(listOf("slack", "webhook"), ids(gate(pairs, failing, FakePlatform(marker(failing)))))
            assertEquals(listOf("webhook"), ids(gate(pairs, passing, FakePlatform(marker(failing)))))
        }

    @Test
    fun `when falls back to the module default and an unknown value delivers`() =
        runTest {
            val pairs = listOf(pair("slack", null, default = "never"), pair("webhook", "sometimes"))
            assertEquals(listOf("webhook"), ids(gate(pairs, failing, platform = null)))
        }

    @Test
    fun `a platform output gets this run notification state`() =
        runTest {
            val out = gate(listOf(pair("platform", null, platform = true)), failing, FakePlatform(marker(passing))).single()
            val state = assertNotNull((out.outputConfig as JsonObject)["notificationState"])
            assertEquals(JsonPrimitive(false), (state as JsonObject)["passed"])
        }

    @Test
    fun `a platform output gets the AI status only when AI is configured`() =
        runTest {
            val provenance = ReviewProvenance(
                analyzers = listOf(AnalyzerProvenance("a", ProvenanceStatus.FAILED)),
                aiStatus = "AI: unavailable — 1/1 calls failed",
            )
            val withAi = failing.copy(provenance = provenance)
            val out = gate(listOf(pair("status", null, platform = true)), withAi, FakePlatform(marker(passing))).single()
            val ai = assertNotNull((out.outputConfig as JsonObject)["aiStatus"]) as JsonObject
            assertEquals(JsonPrimitive("AI: unavailable — 1/1 calls failed"), ai["line"])
            assertEquals(JsonPrimitive(false), ai["passed"])
            val plain = gate(listOf(pair("status", null, platform = true)), failing, FakePlatform(marker(passing))).single()
            assertEquals(null, (plain.outputConfig as JsonObject)["aiStatus"])
        }

    @Test
    fun `an output keyed by MR gets this run target and others are left as they are`() =
        runTest {
            val pairs = listOf(pair("slack", "always", keyed = true), pair("webhook", "always"))
            val out = gate(pairs, failing, FakePlatform(marker(passing)))
            assertEquals(JsonPrimitive("p!1"), (out[0].outputConfig as JsonObject)["notificationTarget"])
            assertEquals(null, (out[1].outputConfig as JsonObject)["notificationTarget"])
            val local = gate(pairs.take(1), failing, platform = null).single()
            assertEquals(null, (local.outputConfig as JsonObject)["notificationTarget"])
        }

    private suspend fun gate(
        pairs: List<ResolvedFormatOutput>,
        report: ValidationReport,
        platform: PlatformServiceModule?,
    ) = NotificationGate(NoOpLogger).apply(pairs, report, platform, "p", "1")

    private fun ids(pairs: List<ResolvedFormatOutput>) = pairs.map { it.outputModule.outputId }

    private fun marker(report: ValidationReport) =
        "Summary\n" + NotificationState.of(report.passed, report.findings.map(FindingSuppression::key)).toMarker()

    private fun pair(
        outputId: String,
        notifyWhen: String?,
        default: String? = null,
        platform: Boolean = false,
        keyed: Boolean = false,
    ): ResolvedFormatOutput {
        val config = JsonObject(listOfNotNull(notifyWhen?.let { "when" to JsonPrimitive(it) }).toMap())
        return ResolvedFormatOutput(FakeFormat, FakeOutput(outputId, platform, default, keyed), JsonObject(emptyMap()), config)
    }

    private object FakeFormat : FormatModule {
        override val formatId = "fake"
        override val id = "fmt-fake"
        override val name = id
        override val version = "1.0"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun format(
            report: ValidationReport,
            config: JsonElement,
        ) = FormattedReport(formatId = formatId, body = "")
    }

    private class FakeOutput(
        override val outputId: String,
        override val requiresPlatformContext: Boolean,
        private val defaultWhen: String?,
        private val keyed: Boolean = false,
    ) : OutputModule {
        override val id = "out-$outputId"
        override val name = id
        override val version = "1.0"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun defaultConfig(): JsonElement =
            JsonObject(
                listOfNotNull(
                    defaultWhen?.let { "when" to JsonPrimitive(it) },
                    ("notificationTarget" to JsonPrimitive("")).takeIf { keyed },
                ).toMap(),
            )

        override suspend fun output(
            report: FormattedReport,
            config: JsonElement,
        ) = Unit
    }

    private class FakePlatform(
        private val summary: String?,
    ) : PlatformServiceModule {
        override val id = "fake-scm"
        override val name = id
        override val version = "1.0"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun fetchMergeRequest(
            projectId: String,
            mrId: String
        ) = error("unused")

        override suspend fun fetchMergeRequestByUrl(url: String) = error("unused")

        override suspend fun addLabels(
            projectId: String,
            mrId: String,
            labels: List<String>
        ) = Unit

        override suspend fun removeLabels(
            projectId: String,
            mrId: String,
            labels: List<String>
        ) = Unit

        override suspend fun setDraft(
            projectId: String,
            mrId: String,
            draft: Boolean
        ) = Unit

        override suspend fun updateTitle(
            projectId: String,
            mrId: String,
            title: String
        ) = Unit

        override suspend fun updateDescription(
            projectId: String,
            mrId: String,
            description: String
        ) = Unit

        override suspend fun approve(
            projectId: String,
            mrId: String
        ) = Unit

        override suspend fun unapprove(
            projectId: String,
            mrId: String
        ) = Unit

        override suspend fun postComment(
            projectId: String,
            mrId: String,
            body: String
        ) = ""

        override suspend fun updateComment(
            projectId: String,
            mrId: String,
            commentId: String,
            body: String
        ) = Unit

        override suspend fun findExistingComment(
            projectId: String,
            mrId: String,
            marker: String
        ): String? = null

        override suspend fun postInlineComment(
            projectId: String,
            mrId: String,
            body: String,
            filePath: String,
            line: Int,
            diffRefs: DiffRefs,
            startLine: Int?,
        ) = ""

        var reads = 0

        override suspend fun findCommentBody(
            projectId: String,
            mrId: String,
            marker: String,
        ): String? {
            reads++
            return summary
        }
    }

    private class RecordingLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }
}
