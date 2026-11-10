package io.karandash.cli.pipeline

import io.karandash.api.module.PipelineExtension
import io.karandash.api.support.rethrowCancellation
import io.karandash.sdk.Logger

/**
 * Runs one [PipelineExtension] hook behind an error boundary: a hook that throws logs a warning and
 * yields [fallback] (the unchanged input, or nothing) instead of aborting the run. Cancellation is rethrown.
 */
internal suspend fun <T> runExtensionHook(
    ext: PipelineExtension,
    hook: String,
    logger: Logger,
    fallback: T,
    block: suspend () -> T,
): T =
    try {
        block()
    } catch (
        // boundary: a third-party pipeline extension hook must not fail the run
        @Suppress("TooGenericExceptionCaught") e: Exception,
    ) {
        e.rethrowCancellation()
        logger.warn("Pipeline extension '${ext.id}' failed in $hook: ${e.message}; continuing without it")
        fallback
    }
