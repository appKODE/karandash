@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.outputs

import io.karandash.Version
import io.karandash.api.PluginContext
import io.karandash.api.ReporterConfig
import io.karandash.api.finding.FormattedReport
import io.karandash.helpers.output.OnError
import io.karandash.helpers.output.handle
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.platform.createDirectories
import io.karandash.platform.safeParent
import io.karandash.sdk.typed.AbstractTypedOutputModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.Serializable

/**
 * Config for [FilesystemOutputModule].
 *
 * @property outputFile Path to the output file. Relative paths resolve from the working directory.
 * @property onError `fail` (default) fails the output when the file cannot be written; `warn` only logs it.
 */
@Serializable
data class FilesystemOutputConfig(
    val outputFile: String,
    val onError: OnError = OnError.FAIL,
) : ReporterConfig

/**
 * Output module that writes a [FormattedReport.body] to a file on disk.
 *
 * Accepts all formats. The file path is configured via [FilesystemOutputConfig.outputFile].
 * Uses `kotlinx-io` [SystemFileSystem] for KMP-compatible file writing.
 */
class FilesystemOutputModule : AbstractTypedOutputModule<FilesystemOutputConfig>(FilesystemOutputConfig.serializer()) {
    override val id: String = ID
    override val name: String = "Filesystem Output"
    override val version: String = Version.VERSION
    override val outputId: String = OUTPUT_ID
    override val description: String = "Writes formatted report body to a file."
    override val supportsFileOutput: Boolean = true
    override val hasExternalSideEffects: Boolean = false

    private var logger: Logger = NoOpLogger

    override suspend fun initialize(context: PluginContext): Boolean {
        logger = context.logger
        return true
    }

    override suspend fun outputTyped(
        report: FormattedReport,
        config: FilesystemOutputConfig,
    ) = config.onError.handle(logger, "Failed to write report to ${config.outputFile}") {
        val path = Path(config.outputFile)
        withContext(Dispatchers.IO) {
            path.safeParent?.let { createDirectories(it) }
            SystemFileSystem.sink(path).buffered().use { sink ->
                sink.writeString(report.body)
            }
        }
        logger.info("Report written to ${config.outputFile}")
    }

    companion object {
        /** Module ID for registry lookups. */
        const val ID = "io.karandash.output.filesystem"

        /** Output identifier. */
        const val OUTPUT_ID = "filesystem"
    }
}
