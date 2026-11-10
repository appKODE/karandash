@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.outputs

import io.karandash.Version
import io.karandash.api.finding.FormattedReport
import io.karandash.sdk.module.AbstractOutputModule
import kotlinx.serialization.json.JsonElement

/**
 * Output module that prints a [FormattedReport.body] to stdout.
 *
 * Accepts all formats. This is the default output for interactive (non-CI) use.
 * Output goes to stdout via [kotlin.io.println] (standard Kotlin multiplatform).
 */
class StdoutOutputModule : AbstractOutputModule() {
    override val id: String = ID
    override val name: String = "Stdout Output"
    override val version: String = Version.VERSION
    override val outputId: String = OUTPUT_ID
    override val description: String = "Prints formatted report body to stdout."
    override val isDefaultInteractive: Boolean = true
    override val hasExternalSideEffects: Boolean = false

    override suspend fun output(
        report: FormattedReport,
        config: JsonElement,
    ) {
        if (report.body.isNotBlank()) {
            println(report.body)
        }
    }

    companion object {
        /** Module ID for registry lookups. */
        const val ID = "io.karandash.output.stdout"

        /** Output identifier. */
        const val OUTPUT_ID = "stdout"
    }
}
