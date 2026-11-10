package io.karandash.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlatformArtifactResolverTest {
    private fun entry(key: String) = ArtifactEntry("https://h/$key", "sha256:$key", key)

    private fun resolved(key: String) = ResolvedArtifact("https://h/$key", "sha256:$key", key)

    @Test
    fun `exact platform wins over every fallback`() {
        val artifacts = listOf("macos-arm64", "js", "wasm", "jvm").associateWith(::entry)

        assertEquals(resolved("macos-arm64"), PlatformArtifactResolver("macos-arm64").resolve(artifacts))
    }

    @Test
    fun `exact platform wins then js then wasm`() {
        val jvm = PlatformArtifactResolver(PlatformArtifactResolver.PLATFORM_JVM)
        val linux = PlatformArtifactResolver("linux-x64")

        assertEquals(resolved("jvm"), jvm.resolve(listOf("js", "wasm", "jvm").associateWith(::entry)))
        assertEquals(resolved("js"), jvm.resolve(listOf("js", "wasm").associateWith(::entry)))
        assertEquals(resolved("linux-x64"), linux.resolve(listOf("js", "wasm", "linux-x64").associateWith(::entry)))
        assertEquals(resolved("js"), linux.resolve(listOf("js", "wasm", "macos-arm64").associateWith(::entry)))
        assertEquals(resolved("wasm"), linux.resolve(listOf("wasm", "jvm").associateWith(::entry)))
    }

    @Test
    fun `jvm artifacts are never offered to native platforms`() {
        assertNull(PlatformArtifactResolver("linux-x64").resolve(mapOf("jvm" to entry("jvm"))))
    }
}
