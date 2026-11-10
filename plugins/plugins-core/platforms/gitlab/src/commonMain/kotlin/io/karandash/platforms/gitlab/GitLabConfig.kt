package io.karandash.platforms.gitlab

import io.karandash.api.PlatformServiceConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Typed configuration for GitLab provider, mutator, and comment-poster modules.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GitLabConfig(
    @JsonNames("baseUrl") val url: String = "https://gitlab.com",
    val token: String? = null,
    @SerialName("approveToken")
    val approveToken: String? = null,
    @SerialName("deleteInlineStrategy")
    val deleteInlineStrategy: DeleteInlineStrategy = DeleteInlineStrategy.DISCUSSION,
) : PlatformServiceConfig {
    companion object
}

/** How GitLab deletes the bot's marked inline comments; an unknown value fails `initialize`. */
@Serializable
enum class DeleteInlineStrategy {
    /** Resolve each marked discussion through the discussions API. */
    @SerialName("discussion")
    DISCUSSION,

    /** Delete the marked MR notes one by one. */
    @SerialName("note")
    NOTE,
}
