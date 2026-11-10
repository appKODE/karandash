package io.karandash.download

import kotlinx.serialization.Serializable

/**
 * Resolved artifact for the current platform.
 *
 * @property url Download URL for the artifact.
 * @property checksum Expected checksum (e.g. "sha256:abc123").
 * @property format File format ("so", "dylib", "dll", "jar", "mjs", "wasm").
 */
@Serializable
data class ResolvedArtifact(
    val url: String,
    val checksum: String,
    val format: String,
)

/**
 * Resolves platform-specific artifacts from registry entries.
 *
 * Given a platform key (e.g. "linux-x64", "macos-arm64", "jvm") and an artifacts map,
 * selects the matching artifact. Falls back to cross-platform formats (JS, WASM)
 * when no native artifact is available.
 *
 * @param platformKey The current platform identifier (from `currentPlatformKey()`).
 */
class PlatformArtifactResolver(
    private val platformKey: String,
) {
    companion object {
        /** Cross-platform fallback keys, tried in order when no native artifact matches. */
        private val CROSS_PLATFORM_FALLBACKS = listOf("js", "wasm")

        /** Platform key for JVM runtime. */
        const val PLATFORM_JVM = "jvm"
    }

    /**
     * Resolve the best artifact for the current platform.
     *
     * Resolution order:
     * 1. Exact platform match (e.g. "linux-x64")
     * 2. Cross-platform fallbacks ("js", "wasm") -- available on all runtimes
     * 3. "jvm" -- only when running on JVM
     *
     * @param artifacts Platform-to-artifact map from registry entry.
     * @return Resolved artifact, or null if no compatible artifact exists.
     */
    fun resolve(artifacts: Map<String, ArtifactEntry>?): ResolvedArtifact? {
        if (artifacts == null) return null
        artifacts[platformKey]?.let { return it.toResolved() }

        for (fallback in CROSS_PLATFORM_FALLBACKS) {
            artifacts[fallback]?.let { return it.toResolved() }
        }

        if (platformKey == PLATFORM_JVM) {
            artifacts[PLATFORM_JVM]?.let { return it.toResolved() }
        }
        return null
    }

    private fun ArtifactEntry.toResolved() = ResolvedArtifact(url = url, checksum = checksum, format = format)
}
