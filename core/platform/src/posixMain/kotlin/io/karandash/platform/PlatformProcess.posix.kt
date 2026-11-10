package io.karandash.platform

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import platform.posix.close
import platform.posix.getpid
import platform.posix.memcpy
import platform.posix.mkstemp
import platform.posix.system

private const val POSIX_STATUS_WORD_SHIFT_THRESHOLD = 255
private const val POSIX_STATUS_BIT_SHIFT = 8

/**
 * One command run by `system()`: [cd] is `cd '<dir>' && ` or empty, [redirects] the stdin/stdout/stderr redirections.
 *
 * Not @Serializable: per-call shell assembly input, never persisted.
 */
internal data class BoundedCommand(
    val command: List<String>,
    val unsetEnv: Set<String>,
    val timeoutSeconds: Int?,
    val cd: String,
    val redirects: String,
)

/** The shell line (after [PosixUnsetEnv.Resolver.prelude]) that runs [run], bounded by its timeout per OS. */
internal expect fun boundedShellCommand(
    run: BoundedCommand,
    resolver: PosixUnsetEnv.Resolver,
): String

/** [PlatformProcess.denySameUserInspection] per OS. */
internal expect fun denyInspection(): InspectionDenial

@OptIn(ExperimentalForeignApi::class)
actual object PlatformProcess {
    actual fun execute(
        command: List<String>,
        workingDir: String?,
        stdin: String?,
        timeoutSeconds: Int?,
        unsetEnv: Set<String>,
    ): ProcessResult {
        val stdoutPath = createTempCapturePath("karandash-stdout")
        val stderrPath = createTempCapturePath("karandash-stderr")
        val resolver = PosixUnsetEnv.Resolver(unsetEnv, stderrPath)
        val stdinPath = if (stdin != null) createTempCapturePath("karandash-stdin") else null

        if (stdinPath != null && stdin != null) {
            Path(stdinPath).writeText(stdin)
        }

        val redirects = (if (stdinPath != null) "< ${quoteShellArg(stdinPath)} " else "") +
            "> ${quoteShellArg(stdoutPath)} 2> ${quoteShellArg(stderrPath)}"
        val cd = if (workingDir != null) "cd ${quoteShellArg(workingDir)} && " else ""
        val shellCommand = boundedShellCommand(BoundedCommand(command, unsetEnv, timeoutSeconds, cd, redirects), resolver)
        val wrappedCommand = resolver.prelude + shellCommand

        try {
            val status = system(wrappedCommand)
            val output = readCaptureFile(stdoutPath)
            val error = readCaptureFile(stderrPath)
            return ProcessResult(
                exitCode = normalizeExitStatus(status),
                output = output,
                error = error
            )
        } finally {
            deleteIfExists(Path(stdoutPath))
            deleteIfExists(Path(stderrPath))
            if (stdinPath != null) deleteIfExists(Path(stdinPath))
        }
    }

    actual suspend fun executeAsync(
        command: List<String>,
        workingDir: String?,
        stdin: String?,
        timeoutSeconds: Int?,
        unsetEnv: Set<String>,
    ): ProcessResult =
        withContext(Dispatchers.IO) {
            execute(command, workingDir, stdin, timeoutSeconds, unsetEnv)
        }

    actual fun spawnInteractive(command: List<String>): InteractiveProcess = spawnPosixInteractive(command)

    actual fun denySameUserInspection(): InspectionDenial = denyInspection()

    private fun quoteShellArg(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    /** Creates an owner-only capture file with mkstemp in [tmpDir]; fails rather than fall back to a guessable name. */
    internal fun createTempCapturePath(
        prefix: String,
        tmpDir: String = PlatformEnvironment.getEnv("TMPDIR")?.takeIf { it.isNotBlank() } ?: "/tmp",
    ): String =
        memScoped {
            val template = "$tmpDir/$prefix-${getpid()}-XXXXXX"
            val bytes = template.encodeToByteArray() + 0
            val buffer = allocArray<ByteVar>(bytes.size)
            bytes.usePinned { pinned ->
                memcpy(buffer, pinned.addressOf(0), bytes.size.convert())
            }
            val fd = mkstemp(buffer)
            if (fd == -1) error("Cannot create a temp capture file in $tmpDir")
            close(fd)
            return buffer.toKString()
        }

    /**
     * system()/pclose() style status values are typically encoded as exit<<8 on POSIX.
     */
    private fun normalizeExitStatus(status: Int): Int {
        if (status < 0) return status
        return if (status > POSIX_STATUS_WORD_SHIFT_THRESHOLD) status ushr POSIX_STATUS_BIT_SHIFT else status
    }
}
