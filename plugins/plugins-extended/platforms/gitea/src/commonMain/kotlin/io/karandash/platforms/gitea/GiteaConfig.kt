package io.karandash.platforms.gitea

import io.karandash.api.PlatformServiceConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Typed configuration for Gitea provider, mutator, and comment-poster modules.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GiteaConfig(
    @JsonNames("baseUrl") val url: String? = null,
    val token: String? = null,
) : PlatformServiceConfig {
    companion object
}
