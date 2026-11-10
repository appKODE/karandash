package io.karandash.download

import kotlinx.serialization.Serializable

/**
 * A single platform artifact in the plugin registry.
 *
 * @property url Download URL for the artifact.
 * @property checksum Expected checksum (e.g. "sha256:abc123").
 * @property format File format ("so", "dylib", "dll", "jar", "mjs", "wasm").
 */
@Serializable
data class ArtifactEntry(
    val url: String,
    val checksum: String,
    val format: String,
)
