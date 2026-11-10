package io.karandash.registry

import kotlinx.serialization.Serializable

/**
 * Information about an installed plugin module.
 */
@Serializable
data class InstalledPluginInfo(
    val id: String,
    val version: String,
    val name: String,
    val sourcePath: String?
)
