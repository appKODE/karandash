package io.karandash.sdk

import io.karandash.duration.KarandashDuration
import io.karandash.sdk.content.DirectorySource
import io.karandash.sdk.content.DirectorySourceSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DirectorySourceSerializerTest {
    private val json = Json { ignoreUnknownKeys = true }

    // --- decode: LocalDir ---

    @Test
    fun `decode plain string produces LocalDir`() {
        val result = json.decodeFromString(DirectorySourceSerializer, "\"/path/to/dir\"")
        assertEquals(DirectorySource.LocalDir("/path/to/dir"), result)
    }

    @Test
    fun `decode object with path key produces LocalDir`() {
        val result = json.decodeFromString(DirectorySourceSerializer, """{"path":"relative/dir"}""")
        assertEquals(DirectorySource.LocalDir("relative/dir"), result)
    }

    @Test
    fun `decode empty string produces LocalDir with empty path`() {
        val result = json.decodeFromString(DirectorySourceSerializer, "\"\"")
        assertEquals(DirectorySource.LocalDir(""), result)
    }

    // --- decode: RemoteDir ---

    @Test
    fun `decode url object produces RemoteDir with url only`() {
        val result = json.decodeFromString(DirectorySourceSerializer, """{"url":"https://example.com/dir"}""")
        assertEquals(DirectorySource.RemoteDir(url = "https://example.com/dir"), result)
    }

    @Test
    fun `decode url object with auth and cache-ttl produces RemoteDir`() {
        val result = json.decodeFromString(
            DirectorySourceSerializer,
            """{"url":"https://example.com/dir","auth":"myToken","cache-ttl":1800}"""
        )
        assertEquals(
            DirectorySource.RemoteDir(url = "https://example.com/dir", auth = "myToken", cacheTtl = KarandashDuration.ofSeconds(1800)),
            result
        )
    }

    @Test
    fun `decode url object with only auth produces RemoteDir with null cacheTtl`() {
        val result = json.decodeFromString(DirectorySourceSerializer, """{"url":"https://x.com","auth":"tok"}""")
        val expected = DirectorySource.RemoteDir(url = "https://x.com", auth = "tok", cacheTtl = null)
        assertEquals(expected, result)
    }

    // --- decode: RepoDir ---

    @Test
    fun `decode repo-dir object produces RepoDir with path only`() {
        val result = json.decodeFromString(DirectorySourceSerializer, """{"repo-dir":"src/main"}""")
        assertEquals(DirectorySource.RepoDir(path = "src/main"), result)
    }

    @Test
    fun `decode repo-dir object with ref produces RepoDir`() {
        val result = json.decodeFromString(DirectorySourceSerializer, """{"repo-dir":"src","ref":"develop"}""")
        assertEquals(DirectorySource.RepoDir(path = "src", ref = "develop"), result)
    }

    // --- decode: errors ---

    @Test
    fun `decode object with unknown key throws SerializationException`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString(DirectorySourceSerializer, """{"unknown":"value"}""")
        }
    }

    @Test
    fun `decode null throws SerializationException`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString(DirectorySourceSerializer, "null")
        }
    }

    // --- encode: LocalDir ---

    @Test
    fun `encode LocalDir produces plain string`() {
        val result = json.encodeToString(DirectorySourceSerializer, DirectorySource.LocalDir("/my/dir"))
        assertEquals("\"/my/dir\"", result)
    }

    // --- encode: RemoteDir ---

    @Test
    fun `encode RemoteDir with url only omits optional fields`() {
        val result = json.encodeToString(DirectorySourceSerializer, DirectorySource.RemoteDir("https://x.com/d"))
        assertEquals("""{"url":"https://x.com/d"}""", result)
    }

    @Test
    fun `encode RemoteDir with all fields includes them`() {
        val source = DirectorySource.RemoteDir(url = "https://x.com/d", auth = "tok", cacheTtl = KarandashDuration.ofSeconds(600))
        val result = json.encodeToString(DirectorySourceSerializer, source)
        assertEquals("""{"url":"https://x.com/d","auth":"tok","cache-ttl":"10m"}""", result)
    }

    // --- encode: RepoDir ---

    @Test
    fun `encode RepoDir with path only omits ref`() {
        val result = json.encodeToString(DirectorySourceSerializer, DirectorySource.RepoDir("src/main"))
        assertEquals("""{"repo-dir":"src/main"}""", result)
    }

    @Test
    fun `encode RepoDir with ref includes it`() {
        val source = DirectorySource.RepoDir(path = "src/main", ref = "feature/x")
        val result = json.encodeToString(DirectorySourceSerializer, source)
        assertEquals("""{"repo-dir":"src/main","ref":"feature/x"}""", result)
    }

    // --- round-trip ---

    @Test
    fun `round-trip LocalDir`() {
        val original = DirectorySource.LocalDir("/some/path")
        val encoded = json.encodeToString(DirectorySourceSerializer, original)
        val decoded = json.decodeFromString(DirectorySourceSerializer, encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `round-trip RemoteDir`() {
        val original = DirectorySource.RemoteDir(url = "https://example.com/dir", auth = "a", cacheTtl = KarandashDuration.ofSeconds(120))
        val encoded = json.encodeToString(DirectorySourceSerializer, original)
        val decoded = json.decodeFromString(DirectorySourceSerializer, encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `round-trip RepoDir`() {
        val original = DirectorySource.RepoDir(path = "docs/api", ref = "v2")
        val encoded = json.encodeToString(DirectorySourceSerializer, original)
        val decoded = json.decodeFromString(DirectorySourceSerializer, encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `RemoteDir optional fields are null when absent`() {
        val result = json.decodeFromString(DirectorySourceSerializer, """{"url":"https://x.com"}""")
            as DirectorySource.RemoteDir
        assertNull(result.auth)
        assertNull(result.cacheTtl)
    }

    @Test
    fun `RepoDir ref is null when absent`() {
        val result = json.decodeFromString(DirectorySourceSerializer, """{"repo-dir":"src"}""")
            as DirectorySource.RepoDir
        assertNull(result.ref)
    }
}
