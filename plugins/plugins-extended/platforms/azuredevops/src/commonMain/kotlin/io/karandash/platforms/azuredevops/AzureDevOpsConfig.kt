package io.karandash.platforms.azuredevops

import io.karandash.api.PlatformServiceConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Typed configuration for Azure DevOps provider, mutator, and comment-poster modules.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AzureDevOpsConfig(
    val token: String? = null,
    /** Organization used when a project ID is given as `project/repository`. */
    val organization: String? = null,
    /** Server base URL; Azure DevOps Server installs set their collection URL here. */
    @JsonNames("baseUrl")
    val url: String? = null,
) : PlatformServiceConfig {
    companion object
}
