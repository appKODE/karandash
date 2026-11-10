package io.karandash.loader

import io.karandash.download.ArtifactEntry
import io.karandash.download.PlatformArtifactResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlatformArtifactResolverTest {
    private fun entry(
        url: String,
        format: String,
        checksum: String = "sha256:abc"
    ) = ArtifactEntry(url = url, checksum = checksum, format = format)

    @Test
    fun `exact platform match returns correct artifact`() {
        val resolver = PlatformArtifactResolver("linux-x64")
        val artifacts = mapOf(
            "linux-x64" to entry("https://example.com/plugin.so", "so", "sha256:linux"),
            "macos-arm64" to entry("https://example.com/plugin.dylib", "dylib", "sha256:macos"),
        )

        val result = resolver.resolve(artifacts)

        assertNotNull(result)
        assertEquals("https://example.com/plugin.so", result.url)
        assertEquals("sha256:linux", result.checksum)
        assertEquals("so", result.format)
    }

    @Test
    fun `cross-platform JS fallback when no native match`() {
        val resolver = PlatformArtifactResolver("linux-x64")
        val artifacts = mapOf(
            "macos-arm64" to entry("https://example.com/plugin.dylib", "dylib"),
            "js" to entry("https://example.com/plugin.mjs", "mjs", "sha256:js"),
        )

        val result = resolver.resolve(artifacts)

        assertNotNull(result)
        assertEquals("https://example.com/plugin.mjs", result.url)
        assertEquals("mjs", result.format)
    }

    @Test
    fun `cross-platform WASM fallback when no native or JS match`() {
        val resolver = PlatformArtifactResolver("linux-x64")
        val artifacts = mapOf(
            "macos-arm64" to entry("https://example.com/plugin.dylib", "dylib"),
            "wasm" to entry("https://example.com/plugin.wasm", "wasm", "sha256:wasm"),
        )

        val result = resolver.resolve(artifacts)

        assertNotNull(result)
        assertEquals("https://example.com/plugin.wasm", result.url)
        assertEquals("wasm", result.format)
    }

    @Test
    fun `JS fallback takes priority over WASM`() {
        val resolver = PlatformArtifactResolver("linux-x64")
        val artifacts = mapOf(
            "js" to entry("https://example.com/plugin.mjs", "mjs"),
            "wasm" to entry("https://example.com/plugin.wasm", "wasm"),
        )

        val result = resolver.resolve(artifacts)

        assertNotNull(result)
        assertEquals("mjs", result.format)
    }

    @Test
    fun `null artifacts map returns null`() {
        val resolver = PlatformArtifactResolver("linux-x64")

        val result = resolver.resolve(artifacts = null)

        assertNull(result)
    }

    @Test
    fun `empty artifacts map returns null for non-JVM`() {
        val resolver = PlatformArtifactResolver("macos-arm64")

        val result = resolver.resolve(artifacts = emptyMap())

        assertNull(result)
    }

    @Test
    fun `JVM platform matches jvm artifact key in artifacts map`() {
        val resolver = PlatformArtifactResolver(PlatformArtifactResolver.PLATFORM_JVM)
        val artifacts = mapOf(
            "jvm" to entry("https://example.com/plugin-jvm.jar", "jar", "sha256:jvm-artifacts"),
        )

        val result = resolver.resolve(artifacts = artifacts)

        assertNotNull(result)
        assertEquals("sha256:jvm-artifacts", result.checksum)
    }

    @Test
    fun `resolved artifact fields match entry exactly`() {
        val resolver = PlatformArtifactResolver("macos-arm64")
        val artifacts = mapOf(
            "macos-arm64" to ArtifactEntry(
                url = "https://cdn.example.com/v2/plugin.dylib",
                checksum = "sha256:deadbeef",
                format = "dylib",
            )
        )

        val result = resolver.resolve(artifacts)

        assertNotNull(result)
        assertEquals("https://cdn.example.com/v2/plugin.dylib", result.url)
        assertEquals("sha256:deadbeef", result.checksum)
        assertEquals("dylib", result.format)
    }
}
