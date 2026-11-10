package io.karandash.api.exception

/**
 * Base exception type for all Karandash-specific errors.
 *
 * All domain exception classes across the codebase should extend this type,
 * enabling uniform error handling at module boundaries. Subclasses carry
 * structured context (e.g. HTTP status codes, module IDs) so that callers
 * can produce actionable error messages without pattern-matching on text.
 *
 * This class is **open** (not sealed) to allow extension from any module.
 * CLI-specific subclasses add exit code semantics; library-level subclasses
 * add domain-specific context.
 *
 * @param message Human-readable error description.
 * @param cause Optional underlying cause.
 */
public open class KarandashException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
