package io.karandash.enrichment.symbol

import io.karandash.api.support.suspendCatching
import io.karandash.platform.PlatformProcess
import io.karandash.platform.ProcessResult
import io.karandash.platform.ProcessTimeouts
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger

/** Runs a command in an optional working directory with a timeout; a seam over [PlatformProcess.executeAsync]. */
internal typealias ProcessRunner = suspend (command: List<String>, workingDir: String?, timeoutSeconds: Int) -> ProcessResult

internal val platformProcessRunner: ProcessRunner = { command, workingDir, timeoutSeconds ->
    PlatformProcess.executeAsync(command, workingDir, timeoutSeconds = timeoutSeconds)
}

/**
 * Thin subprocess wrapper around the `ast-grep` CLI.
 *
 * All methods offload blocking subprocess calls to [PlatformProcess.executeAsync].
 * A [probe] check must be performed before any [scan] calls to avoid spawning
 * processes when the binary is missing.
 */
internal object AstGrepRunner {
    const val DEFAULT_BINARY = "ast-grep"
    private const val VERSION_FLAG = "--version"
    private const val TIMEOUT_SECONDS = ProcessTimeouts.DEFAULT_SECONDS

    /**
     * Returns `true` if the given ast-grep binary responds to `--version`.
     * Used during provider `initialize()` to detect a missing binary without
     * throwing.
     */
    suspend fun probe(
        binaryPath: String = DEFAULT_BINARY,
        logger: Logger = NoOpLogger,
        runProcess: ProcessRunner = platformProcessRunner,
    ): Boolean =
        suspendCatching { runProcess(listOf(binaryPath, VERSION_FLAG), null, TIMEOUT_SECONDS).isSuccess }.getOrElse {
            logger.debug("ast-grep: binary '$binaryPath' not found on PATH — symbol enrichment disabled")
            false
        }

    /**
     * Runs `ast-grep run --pattern <pattern> --lang <lang> --json=stream <filePath>` in [directory]
     * and returns the raw NDJSON stdout (one match per line).
     *
     * Returns `null` on non-zero exit (e.g., no matches - ast-grep exits 1),
     * timeout, or binary failure. Callers should treat `null` as "no matches"
     * rather than an error.
     */
    suspend fun scan(
        pattern: String,
        language: AstGrepLanguage,
        filePath: String,
        directory: String,
        binaryPath: String = DEFAULT_BINARY,
        logger: Logger = NoOpLogger,
        runProcess: ProcessRunner = platformProcessRunner,
    ): String? =
        suspendCatching {
            val result = runProcess(
                listOf(
                    binaryPath,
                    "run",
                    "--pattern",
                    pattern,
                    "--lang",
                    language.langId,
                    "--json=stream",
                    filePath,
                ),
                directory,
                TIMEOUT_SECONDS,
            )
            if (result.isSuccess) result.output else null
        }.onFailure { logger.warn("ast-grep: scan failed for ${language.langId}: ${it.message}") }
            .getOrNull()
}
