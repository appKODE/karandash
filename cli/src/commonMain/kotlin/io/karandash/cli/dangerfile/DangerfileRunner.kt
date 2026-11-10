package io.karandash.cli.dangerfile

import io.karandash.api.support.rethrowCancellation
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.platform.PlatformProcess
import io.karandash.platform.ProcessResult
import io.karandash.platform.ProcessTimeouts
import io.karandash.platform.absolutePathOrSelf
import io.karandash.platform.createTempFileInDir
import io.karandash.platform.deleteIfExists
import io.karandash.platform.readText
import io.karandash.platform.tempDirectory
import io.karandash.platform.writeText
import io.karandash.sdk.Logger
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Duration

/**
 * Runs a Dangerfile (JS or KTS) as an external subprocess and returns its findings.
 *
 * **JS**: Writes a Node.js bridge script to a temp file, passes karandash MR data
 * as DangerDSL JSON via a CLI argument, evaluates the Dangerfile using Node's `vm` module.
 *
 * **KTS**: Passes DangerDSL JSON via stdin to `danger-kotlin` using the
 * standard `danger://dsl/<path>` protocol, reads results from the sentinel.
 *
 * The subprocess runs off the calling thread and is killed after [timeout] (`timeouts.dangerfile`). A non-zero exit,
 * a missing sentinel, and a missing or unparsable results file each log a warning and yield no findings.
 *
 * @param workingDir Working directory for subprocess execution
 */
class DangerfileRunner(
    private val workingDir: String,
    private val logger: Logger,
    private val timeout: Duration,
) {
    /**
     * Run a detected Dangerfile against the given merge request.
     *
     * @param dangerfile The detected Dangerfile (path and type)
     * @param mr The merge request data
     * @return List of findings produced by the Dangerfile, empty if none or on error
     */
    suspend fun run(
        dangerfile: DetectedDangerfile,
        mr: MergeRequest
    ): List<Finding> =
        when (dangerfile.type) {
            DangerfileType.JS -> runJs(dangerfile.absolutePath, mr)
            DangerfileType.KTS -> runKts(dangerfile.absolutePath, mr)
        }

    private suspend fun runJs(
        dangerfilePath: String,
        mr: MergeRequest
    ): List<Finding> {
        val dslFile = createTempFileInDir(tempDirectory(), "karandash-dsl-", ".json")
        val bridgeFile = createTempFileInDir(tempDirectory(), "karandash-bridge-", ".cjs")
        try {
            dslFile.writeText(DangerDslSerializer.serialize(mr))
            bridgeFile.writeText(NODE_BRIDGE_SCRIPT)
            return results(execute(listOf("node", bridgeFile.toString(), dangerfilePath, dslFile.toString()), stdin = null))
        } finally {
            deleteIfExists(dslFile)
            deleteIfExists(bridgeFile)
        }
    }

    private suspend fun runKts(
        dangerfilePath: String,
        mr: MergeRequest
    ): List<Finding> {
        val dslFile = createTempFileInDir(tempDirectory(), "karandash-dsl-", ".json").absolutePathOrSelf()
        try {
            dslFile.writeText(DangerDslSerializer.serialize(mr))
            return results(
                execute(listOf("danger-kotlin", "ci", "--dangerfile", dangerfilePath), stdin = "danger://dsl/$dslFile\n"),
            )
        } finally {
            deleteIfExists(dslFile)
        }
    }

    private suspend fun execute(
        command: List<String>,
        stdin: String?,
    ): ProcessResult =
        PlatformProcess.executeAsync(
            command = command,
            workingDir = workingDir,
            stdin = stdin,
            timeoutSeconds = ProcessTimeouts.ceilSeconds(timeout),
        )

    private fun results(result: ProcessResult): List<Finding> {
        if (result.exitCode == ProcessTimeouts.EXIT_CODE) {
            logger.warn("Dangerfile: killed after $timeout (timeouts.dangerfile); no findings")
            return emptyList()
        }
        if (!result.isSuccess) logger.warn("Dangerfile: exited ${result.exitCode}: ${result.error.trim().take(MAX_STDERR_CHARS)}")
        val sentinel = result.output.lines().firstOrNull { it.startsWith(SENTINEL) }
        if (sentinel == null) {
            logger.warn("Dangerfile: no '$SENTINEL' line in its output; no findings")
            return emptyList()
        }
        val resultsFile = Path(sentinel.removePrefix(SENTINEL).trim())
        if (!SystemFileSystem.exists(resultsFile)) {
            logger.warn("Dangerfile: results file '$resultsFile' does not exist; no findings")
            return emptyList()
        }
        return try {
            DangerResultsParser.parse(resultsFile.readText())
        } catch (ignored: Exception) {
            // boundary: results file is written by a user Dangerfile script
            ignored.rethrowCancellation()
            logger.warn("Dangerfile: results file '$resultsFile' is not valid Danger results: ${ignored.message}")
            emptyList()
        } finally {
            deleteIfExists(resultsFile)
        }
    }

    private companion object {
        const val SENTINEL = "danger-results:/"
        const val MAX_STDERR_CHARS = 500
    }
}
