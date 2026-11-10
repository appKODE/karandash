package io.karandash.inspector

import kotlinx.serialization.Serializable

/**
 * Metadata extracted from a plugin file.
 */
@Serializable
data class PluginFileMetadata(
    val id: String,
    val name: String,
    val version: String,
)
