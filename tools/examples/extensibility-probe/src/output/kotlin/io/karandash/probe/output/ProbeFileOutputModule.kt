package io.karandash.probe.output

import io.karandash.sdk.PluginContext
import io.karandash.sdk.finding.FormattedReport
import io.karandash.sdk.module.OutputModule
import kotlinx.serialization.json.JsonElement

/**
 * Output-hints probe: a file-writing output that tells the CLI its preferred format and that it writes a file,
 * through [OutputModule] flags instead of hardcoded output IDs in `FormatOutputPlanner`/`ReportGenerator`.
 *
 * Red until the SDK adds `OutputModule.preferredFormat` and `OutputModule.supportsFileOutput`.
 */
class ProbeFileOutputModule : OutputModule {
    override val id: String = ID
    override val name: String = "Extensibility probe — file output"
    override val version: String = "0.0.0"
    override val outputId: String = ID
    override val acceptedFormats: Set<String> = setOf(FORMAT)
    override val preferredFormat: String = FORMAT
    override val supportsFileOutput: Boolean = true

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun output(
        report: FormattedReport,
        config: JsonElement,
    ) {
        // The probe only has to compile against the flags; writing the file is not under test.
        check(report.formatId == FORMAT) { "probe output got unexpected format ${report.formatId}" }
    }

    companion object {
        const val ID = "probe-file"
        const val FORMAT = "json"
    }
}
