package io.karandash.platform

import com.sun.jna.Function
import com.sun.jna.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

private const val PR_SET_DUMPABLE = 4

actual object PlatformProcess {
    actual fun execute(
        command: List<String>,
        workingDir: String?,
        stdin: String?,
        timeoutSeconds: Int?,
        unsetEnv: Set<String>,
    ): ProcessResult {
        val builder = ProcessBuilder(command)
            .redirectErrorStream(false)
        // ProcessBuilder resolves command[0] with the JVM's own PATH, so PATH may be unset here too.
        builder.environment().keys.removeAll(unsetEnv)
        if (workingDir != null) {
            builder.directory(File(workingDir))
        }
        val process = builder.start()

        if (stdin != null) {
            process.outputStream.bufferedWriter().use { it.write(stdin) }
        }

        // Drain stdout and stderr on separate threads to prevent pipe deadlock.
        // Sequential draining blocks when the subprocess fills the stderr pipe buffer
        // while the parent is still reading stdout.
        val executor = Executors.newFixedThreadPool(2)
        val stdoutFuture: Future<String> = executor.submit<String> {
            process.inputStream.bufferedReader().readText()
        }
        val stderrFuture: Future<String> = executor.submit<String> {
            process.errorStream.bufferedReader().readText()
        }

        val completed = if (timeoutSeconds != null) {
            process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        } else {
            process.waitFor()
            true
        }

        if (!completed) {
            process.destroyForcibly()
            executor.shutdownNow()
            return ProcessResult(
                exitCode = ProcessTimeouts.EXIT_CODE,
                output = "",
                error = "Process timed out after ${timeoutSeconds}s"
            )
        }

        val output = stdoutFuture.get()
        val error = stderrFuture.get()
        executor.shutdown()
        return ProcessResult(process.exitValue(), output, error)
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

    actual fun spawnInteractive(command: List<String>): InteractiveProcess =
        try {
            JvmInteractiveProcess(ProcessBuilder(command).start())
        } catch (e: IOException) {
            error("Cannot start ${command.firstOrNull()}: ${e.message}")
        }

    actual fun denySameUserInspection(): InspectionDenial =
        denyInspectionViaPrctl(Platform.isLinux()) {
            Function.getFunction("c", "prctl").invokeInt(arrayOf<Any>(PR_SET_DUMPABLE, 0L))
        }
}

/** [InteractiveProcess] over a [Process]: buffered UTF-8 readers and writer on its pipes. */
private class JvmInteractiveProcess(
    private val process: Process,
) : InteractiveProcess {
    private val stdin = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private val stdout = process.inputStream.bufferedReader(Charsets.UTF_8)
    private val stderr = process.errorStream.bufferedReader(Charsets.UTF_8)

    override fun writeLine(line: String): Boolean =
        try {
            stdin.write(line)
            stdin.write("\n")
            stdin.flush()
            true
        } catch (_: IOException) {
            false
        }

    override fun readLine(): String? = readOrEnd(stdout)

    override fun readErrorLine(): String? = readOrEnd(stderr)

    override fun closeStdin() {
        try {
            stdin.close()
        } catch (_: IOException) {
            // The pipe is already broken: the child is gone, which is what closing signals anyway.
        }
    }

    override fun kill() {
        process.destroyForcibly()
        process.waitFor()
    }

    private fun readOrEnd(reader: BufferedReader): String? =
        try {
            reader.readLine()
        } catch (_: IOException) {
            null
        }
}

/**
 * Runs [prctl] on Linux and maps its result. JNA may be missing or fail to bind libc, which surfaces as a
 * [LinkageError] (`UnsatisfiedLinkError`, `NoClassDefFoundError`) that must not abort the run.
 */
internal fun denyInspectionViaPrctl(
    isLinux: Boolean,
    prctl: () -> Int,
): InspectionDenial {
    if (!isLinux) return InspectionDenial.NotApplicable
    return try {
        if (prctl() == 0) InspectionDenial.Applied else InspectionDenial.Failed
    } catch (_: LinkageError) {
        InspectionDenial.Failed
    }
}
