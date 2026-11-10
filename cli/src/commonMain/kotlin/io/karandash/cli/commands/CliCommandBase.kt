package io.karandash.cli.commands

import io.karandash.api.exception.CliException
import io.karandash.api.support.rethrowCancellation
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.SuspendCliktCommand
import io.karandash.cli.errors.ExitCodes
import io.karandash.sdk.Logger

/**
 * Abstract base for CLI commands with standardized lifecycle handling.
 *
 * Subclasses implement [execute] to perform their work. The base class provides:
 * - Uniform error handling: [CliException] subclasses produce user-facing messages
 *   with exit codes; unexpected exceptions produce internal error messages.
 * - Access to [bootstrap] for plugin registry and context creation.
 *
 * Commands that need custom result handling override [handleResult].
 */
abstract class CliCommandBase(
    name: String,
    protected val bootstrap: PluginBootstrap,
) : SuspendCliktCommand(name = name) {
    /** Logger resolved from the bootstrap. */
    protected val logger: Logger get() = bootstrap.logger

    /**
     * Perform the command's work.
     *
     * @return Exit code (0 = success).
     */
    protected abstract suspend fun execute(): Int

    /**
     * Handle a [CliException] with structured error output.
     *
     * Override to customize error handling for specific exception types.
     *
     * @return Exit code from the exception.
     */
    protected open fun handleError(e: CliException): Int {
        echo("[ERROR] ${e.message ?: "Unknown error"}", err = true)
        return e.exitCode
    }

    @Suppress("TooGenericExceptionCaught") // boundary: top-level CLI command
    final override suspend fun suspendRun() {
        val exitCode = try {
            execute()
        } catch (e: CliException) {
            handleError(e)
        } catch (e: Exception) {
            // boundary: unexpected command failure; cancellation (global timeout) propagates to runCli
            e.rethrowCancellation()
            // At NORMAL the bootstrap logger is a no-op, so the one-line error goes straight to stderr.
            echo("[ERROR] Internal error: ${e::class.simpleName}: ${e.message ?: "no message"}", err = true)
            logger.debug(e.stackTraceToString())
            ExitCodes.INTERNAL_ERROR
        }
        if (exitCode != ExitCodes.SUCCESS) {
            throw com.github.ajalt.clikt.core
                .ProgramResult(exitCode)
        }
    }
}
