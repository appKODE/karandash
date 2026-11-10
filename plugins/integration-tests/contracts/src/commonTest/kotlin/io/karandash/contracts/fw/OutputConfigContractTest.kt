package io.karandash.contracts.fw

import io.karandash.api.finding.FormattedReport
import io.karandash.contracts.support.MarkerRecordingPlatformService
import io.karandash.models.DiffRefs
import io.karandash.plugins.outputs.PlatformOutputModule
import io.karandash.testfixtures.TestPluginContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Output config keys are parsed but never read.
 *
 * `PlatformOutputConfig.commentMarker` is documented as the summary-comment dedup marker override,
 * but `postSummaryComment` only uses `report.commentMarker`.
 */
class OutputConfigContractTest {
    @Test
    fun `platform output commentMarker config overrides the summary marker`() =
        runTest {
            val config = buildJsonObject { put("commentMarker", "<!-- team-bot -->") }
            val platform = MarkerRecordingPlatformService()
            val module = PlatformOutputModule()
            module.initialize(TestPluginContext(pluginConfig = config))
            module.configurePlatformService(
                platform,
                "proj",
                "1",
                DiffRefs(baseSha = "aaa", headSha = "bbb", startSha = "aaa"),
            )
            val report = FormattedReport(
                formatId = "markdown",
                body = "# Report",
                inlineComments = emptyList(),
                commentMarker = "<!-- karandash -->",
                inlineMarker = "<!-- karandash-inline -->",
                changedFiles = null,
            )

            module.output(report, config)

            assertEquals(
                listOf("<!-- team-bot -->"),
                platform.summaryMarkers,
                "configured commentMarker ignored; summary lookup used ${platform.summaryMarkers}",
            )
        }
}
