package io.karandash.platforms.gerrit

import io.karandash.api.PlatformServiceConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Typed configuration for Gerrit provider, mutator, and comment-poster modules.
 *
 * @property notify Who Gerrit emails about the bot's reviews ([GerritNotify.OWNER] by default).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GerritConfig(
    @JsonNames("baseUrl") val url: String? = null,
    val username: String? = null,
    val token: String? = null,
    val notify: GerritNotify = GerritNotify.OWNER,
) : PlatformServiceConfig {
    companion object
}

/** Gerrit's `notify` of a review: who gets an email about it. */
@Serializable
enum class GerritNotify {
    /** Nobody. */
    NONE,

    /** The change owner. */
    OWNER,

    /** The owner and the reviewers. */
    OWNER_REVIEWERS,

    /** Everyone watching the change. */
    ALL,
}
