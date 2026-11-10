package io.karandash.platform

import kotlinx.serialization.Serializable

expect object PlatformProcess {
    /**
     * Runs [command] and captures its output.
     *
     * [unsetEnv] names variables removed from the child's environment at exec time, so they are absent from its
     * initial environment block (`/proc/<pid>/environ`, `ps -E`), not just hidden from a runtime API. `command[0]`
     * is still resolved with this process's `PATH`. On Windows the child gets an explicit environment block without
     * them (names matched case-insensitively).
     *
     * [timeoutSeconds] defaults to [ProcessTimeouts.DEFAULT_SECONDS]; pass `null` explicitly for an unbounded child.
     */
    fun execute(
        command: List<String>,
        workingDir: String? = null,
        stdin: String? = null,
        timeoutSeconds: Int? = ProcessTimeouts.DEFAULT_SECONDS,
        unsetEnv: Set<String> = emptySet(),
    ): ProcessResult

    /**
     * Suspend variant of [execute] that offloads the blocking call to [kotlinx.coroutines.Dispatchers.IO].
     *
     * Prefer this overload from coroutine contexts to avoid blocking the calling thread.
     */
    suspend fun executeAsync(
        command: List<String>,
        workingDir: String? = null,
        stdin: String? = null,
        timeoutSeconds: Int? = ProcessTimeouts.DEFAULT_SECONDS,
        unsetEnv: Set<String> = emptySet(),
    ): ProcessResult

    /**
     * Starts [command] with piped stdin, stdout and stderr and returns at once, for a long-lived child spoken to in
     * lines (the JAR plugin bridge). `command[0]` is resolved with `PATH`; the child inherits this environment and
     * working directory. Fails with [IllegalStateException] when the child cannot be started.
     */
    fun spawnInteractive(command: List<String>): InteractiveProcess

    /**
     * Marks this process non-dumpable (`prctl(PR_SET_DUMPABLE, 0)`) on Linux, so same-uid processes it spawns can
     * no longer read its `/proc/<pid>/environ`, `mem`, `maps` or attach with ptrace. Children are unaffected:
     * `execve` resets the flag. Side effects for the rest of the run: no core dumps, and tools that need
     * `/proc/<pid>` access (`jcmd`, `gdb`, profilers) cannot attach. Idempotent.
     *
     * @return [InspectionDenial.Applied] when the process is now non-dumpable, [InspectionDenial.Failed] when the
     * call was attempted on Linux and did not succeed, [InspectionDenial.NotApplicable] on other platforms.
     */
    fun denySameUserInspection(): InspectionDenial
}

/** Outcome of [PlatformProcess.denySameUserInspection]. */
enum class InspectionDenial {
    /** The platform has no such control (macOS, Windows), so nothing was attempted. */
    NotApplicable,

    /** The process is now non-dumpable. */
    Applied,

    /** The control exists (Linux) but could not be set, so same-user processes can still inspect this process. */
    Failed,
}

/**
 * Result from executing a process.
 *
 * Includes convenience methods to reduce boilerplate in callers.
 * All logic in commonMain for consistent behavior across platforms.
 */
@Serializable
data class ProcessResult(
    val exitCode: Int,
    val output: String,
    val error: String
) {
    /**
     * True if the process completed successfully (exit code 0).
     */
    val isSuccess: Boolean get() = exitCode == 0

    /**
     * True if the process failed (non-zero exit code).
     */
    val isFailed: Boolean get() = exitCode != 0

    /**
     * True if the process produced output.
     */
    val hasOutput: Boolean get() = output.isNotEmpty()

    /**
     * True if the process produced error output.
     */
    val hasError: Boolean get() = error.isNotEmpty()

    /**
     * Get output if successful, null if failed.
     * Useful for safe unwrapping: `result.outputOrNull()?.trim()`
     */
    fun outputOrNull(): String? = if (isSuccess) output else null

    /**
     * Get output if successful, throw exception if failed.
     * @param message Custom error message (defaults to process error output)
     * @throws IllegalStateException if process failed
     */
    fun outputOrThrow(message: String? = null): String {
        if (isFailed) {
            val errorMsg = message ?: error.ifEmpty { "Process failed with exit code: $exitCode" }
            error(errorMsg)
        }
        return output
    }
}
