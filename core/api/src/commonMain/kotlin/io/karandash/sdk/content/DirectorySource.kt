package io.karandash.sdk.content

import io.karandash.duration.KarandashDuration
import kotlinx.serialization.Serializable

/**
 * Universal directory source for config fields referencing directories.
 */
@Serializable(with = DirectorySourceSerializer::class)
public sealed class DirectorySource {
    /** Local directory path - relative to config dir or absolute. */
    @Serializable
    public data class LocalDir(
        val path: String
    ) : DirectorySource()

    /** Remote directory - synced to local temp before use. */
    @Serializable
    public data class RemoteDir(
        val url: String,
        val auth: String? = null,
        val cacheTtl: KarandashDuration? = null,
    ) : DirectorySource()

    /** Directory from the repo being validated via provider API. */
    @Serializable
    public data class RepoDir(
        val path: String,
        val ref: String? = null,
    ) : DirectorySource()
}
