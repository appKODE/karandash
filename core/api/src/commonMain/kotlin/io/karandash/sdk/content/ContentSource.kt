package io.karandash.sdk.content

import io.karandash.duration.KarandashDuration
import kotlinx.serialization.Serializable

/**
 * Universal content source - replaces 6 ad-hoc file reference syntaxes.
 * Any config field can use this type; the framework handles loading.
 */
@Serializable(with = ContentSourceSerializer::class)
public sealed class ContentSource {
    /** Plain string value. */
    @Serializable
    public data class Inline(
        val value: String
    ) : ContentSource()

    /** Load from local file relative to config directory. */
    @Serializable
    public data class LocalFile(
        val path: String,
        val auth: String? = null,
        val resolution: String? = null,
    ) : ContentSource()

    /** Load from remote URL with optional named auth reference. */
    @Serializable
    public data class RemoteFile(
        val url: String,
        val auth: String? = null,
        val cacheTtl: KarandashDuration? = null,
    ) : ContentSource()

    /** Load from the repo being validated via provider API. */
    @Serializable
    public data class RepoFile(
        val path: String,
        val ref: String? = null,
    ) : ContentSource()

    /** Multiple sources - resolved in parallel, concatenated. */
    @Serializable
    public data class Multiple(
        val sources: List<ContentSource>,
        val separator: String = "\n\n",
    ) : ContentSource()
}
