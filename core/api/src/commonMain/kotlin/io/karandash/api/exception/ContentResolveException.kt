package io.karandash.api.exception

/**
 * Thrown when content resolution fails for any [io.karandash.sdk.content.ContentSource] type.
 *
 * @param sourceType The type name of the source that failed (e.g. "LocalFile", "RemoteFile").
 * @param reference The path, URL, or identifier that could not be resolved.
 * @param message Human-readable description of the failure.
 * @param cause Optional underlying cause.
 */
public class ContentResolveException(
    public val sourceType: String,
    public val reference: String,
    message: String,
    cause: Throwable? = null,
) : KarandashException(message, cause)
