package io.karandash.platforms.bitbucket

import io.karandash.api.PlatformServiceConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Typed configuration for Bitbucket provider, mutator, and comment-poster modules.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BitbucketConfig(
    @JsonNames("baseUrl") val url: String = DEFAULT_URL,
    val token: String? = null,
    /** When set, [token] is an app password sent as HTTP Basic `username:token` instead of a Bearer token. */
    val username: String? = null,
) : PlatformServiceConfig {
    companion object {
        const val DEFAULT_URL = "https://api.bitbucket.org/2.0"
    }
}
