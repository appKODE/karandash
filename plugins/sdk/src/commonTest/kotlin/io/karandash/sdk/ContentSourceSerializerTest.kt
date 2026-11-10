package io.karandash.sdk

import io.karandash.duration.KarandashDuration
import io.karandash.sdk.content.ContentSource
import io.karandash.sdk.content.ContentSourceSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ContentSourceSerializerTest {
    private val json = Json { ignoreUnknownKeys = true }

    // --- decode: Inline ---

    @Test
    fun `decode plain string produces Inline`() {
        val result = json.decodeFromString(ContentSourceSerializer, "\"hello world\"")
        assertEquals(ContentSource.Inline("hello world"), result)
    }

    @Test
    fun `decode empty string produces Inline with empty value`() {
        val result = json.decodeFromString(ContentSourceSerializer, "\"\"")
        assertEquals(ContentSource.Inline(""), result)
    }

    @Test
    fun `decode explicit inline object produces Inline`() {
        val result = json.decodeFromString(ContentSourceSerializer, """{"inline":"explicit value"}""")
        assertEquals(ContentSource.Inline("explicit value"), result)
    }

    // --- decode: LocalFile ---

    @Test
    fun `decode file object produces LocalFile with path only`() {
        val result = json.decodeFromString(ContentSourceSerializer, """{"file":"rules/policy.txt"}""")
        assertEquals(ContentSource.LocalFile(path = "rules/policy.txt"), result)
    }

    @Test
    fun `decode file object with auth and resolution produces LocalFile`() {
        val result = json.decodeFromString(
            ContentSourceSerializer,
            """{"file":"path/to/file","auth":"myToken","resolution":"strict"}"""
        )
        assertEquals(
            ContentSource.LocalFile(path = "path/to/file", auth = "myToken", resolution = "strict"),
            result
        )
    }

    @Test
    fun `decode file object with only auth produces LocalFile with null resolution`() {
        val result = json.decodeFromString(ContentSourceSerializer, """{"file":"f.txt","auth":"tok"}""")
        assertEquals(ContentSource.LocalFile(path = "f.txt", auth = "tok", resolution = null), result)
    }

    // --- decode: RemoteFile ---

    @Test
    fun `decode url object produces RemoteFile with url only`() {
        val result = json.decodeFromString(ContentSourceSerializer, """{"url":"https://example.com/file.txt"}""")
        assertEquals(ContentSource.RemoteFile(url = "https://example.com/file.txt"), result)
    }

    @Test
    fun `decode url object with auth and cache-ttl produces RemoteFile`() {
        val result = json.decodeFromString(
            ContentSourceSerializer,
            """{"url":"https://example.com/f.txt","auth":"bearer","cache-ttl":3600}"""
        )
        assertEquals(
            ContentSource.RemoteFile(url = "https://example.com/f.txt", auth = "bearer", cacheTtl = KarandashDuration.ofSeconds(3600)),
            result
        )
    }

    // --- decode: RepoFile ---

    @Test
    fun `decode repo-file object produces RepoFile with path only`() {
        val result = json.decodeFromString(ContentSourceSerializer, """{"repo-file":"docs/rules.md"}""")
        assertEquals(ContentSource.RepoFile(path = "docs/rules.md"), result)
    }

    @Test
    fun `decode repo-file object with ref produces RepoFile`() {
        val result = json.decodeFromString(ContentSourceSerializer, """{"repo-file":"README.md","ref":"main"}""")
        assertEquals(ContentSource.RepoFile(path = "README.md", ref = "main"), result)
    }

    // --- decode: Multiple ---

    @Test
    fun `decode array produces Multiple with correct sources`() {
        val result = json.decodeFromString(
            ContentSourceSerializer,
            """["first",{"file":"second.txt"},{"url":"https://third.com"}]"""
        )
        val expected = ContentSource.Multiple(
            sources = listOf(
                ContentSource.Inline("first"),
                ContentSource.LocalFile(path = "second.txt"),
                ContentSource.RemoteFile(url = "https://third.com"),
            )
        )
        assertEquals(expected, result)
    }

    @Test
    fun `decode nested Multiple in array`() {
        val result = json.decodeFromString(
            ContentSourceSerializer,
            """[["a","b"],"c"]"""
        )
        val expected = ContentSource.Multiple(
            sources = listOf(
                ContentSource.Multiple(
                    sources = listOf(ContentSource.Inline("a"), ContentSource.Inline("b"))
                ),
                ContentSource.Inline("c"),
            )
        )
        assertEquals(expected, result)
    }

    // --- decode: errors ---

    @Test
    fun `decode object with unknown key throws SerializationException`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString(ContentSourceSerializer, """{"unknown":"value"}""")
        }
    }

    @Test
    fun `decode null throws SerializationException`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString(ContentSourceSerializer, "null")
        }
    }

    // --- encode: Inline ---

    @Test
    fun `encode Inline produces plain string`() {
        val result = json.encodeToString(ContentSourceSerializer, ContentSource.Inline("hello"))
        assertEquals("\"hello\"", result)
    }

    // --- encode: LocalFile ---

    @Test
    fun `encode LocalFile with path only omits optional fields`() {
        val result = json.encodeToString(ContentSourceSerializer, ContentSource.LocalFile("myfile.txt"))
        assertEquals("""{"file":"myfile.txt"}""", result)
    }

    @Test
    fun `encode LocalFile with all fields includes them`() {
        val source = ContentSource.LocalFile(path = "p.txt", auth = "tok", resolution = "strict")
        val result = json.encodeToString(ContentSourceSerializer, source)
        assertEquals("""{"file":"p.txt","auth":"tok","resolution":"strict"}""", result)
    }

    // --- encode: RemoteFile ---

    @Test
    fun `encode RemoteFile with url only omits optional fields`() {
        val result = json.encodeToString(ContentSourceSerializer, ContentSource.RemoteFile("https://x.com"))
        assertEquals("""{"url":"https://x.com"}""", result)
    }

    @Test
    fun `encode RemoteFile with cache-ttl includes it`() {
        val source = ContentSource.RemoteFile(url = "https://x.com", cacheTtl = KarandashDuration.ofSeconds(300))
        val result = json.encodeToString(ContentSourceSerializer, source)
        assertEquals("""{"url":"https://x.com","cache-ttl":"5m"}""", result)
    }

    // --- encode: RepoFile ---

    @Test
    fun `encode RepoFile with path only omits ref`() {
        val result = json.encodeToString(ContentSourceSerializer, ContentSource.RepoFile("docs/p.md"))
        assertEquals("""{"repo-file":"docs/p.md"}""", result)
    }

    @Test
    fun `encode RepoFile with ref includes it`() {
        val source = ContentSource.RepoFile(path = "docs/p.md", ref = "develop")
        val result = json.encodeToString(ContentSourceSerializer, source)
        assertEquals("""{"repo-file":"docs/p.md","ref":"develop"}""", result)
    }

    // --- encode: Multiple ---

    @Test
    fun `encode Multiple produces array`() {
        val source = ContentSource.Multiple(
            sources = listOf(ContentSource.Inline("a"), ContentSource.LocalFile("b.txt"))
        )
        val result = json.encodeToString(ContentSourceSerializer, source)
        assertEquals("""["a",{"file":"b.txt"}]""", result)
    }

    // --- round-trip ---

    @Test
    fun `round-trip Inline`() {
        val original = ContentSource.Inline("round trip")
        val encoded = json.encodeToString(ContentSourceSerializer, original)
        val decoded = json.decodeFromString(ContentSourceSerializer, encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `round-trip LocalFile`() {
        val original = ContentSource.LocalFile(path = "file.txt", auth = "tok", resolution = "res")
        val encoded = json.encodeToString(ContentSourceSerializer, original)
        val decoded = json.decodeFromString(ContentSourceSerializer, encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `round-trip RemoteFile`() {
        val original = ContentSource.RemoteFile(url = "https://ex.com", auth = "a", cacheTtl = KarandashDuration.ofSeconds(60))
        val encoded = json.encodeToString(ContentSourceSerializer, original)
        val decoded = json.decodeFromString(ContentSourceSerializer, encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `round-trip RepoFile`() {
        val original = ContentSource.RepoFile(path = "README.md", ref = "main")
        val encoded = json.encodeToString(ContentSourceSerializer, original)
        val decoded = json.decodeFromString(ContentSourceSerializer, encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `round-trip Multiple`() {
        val original = ContentSource.Multiple(
            sources = listOf(ContentSource.Inline("x"), ContentSource.RepoFile("f.md"))
        )
        val encoded = json.encodeToString(ContentSourceSerializer, original)
        val decoded = json.decodeFromString(ContentSourceSerializer, encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `RemoteFile cacheTtl is null when not present`() {
        val result = json.decodeFromString(ContentSourceSerializer, """{"url":"https://ex.com"}""")
        assertNull((result as ContentSource.RemoteFile).cacheTtl)
    }

    @Test
    fun `LocalFile optional fields are null when absent`() {
        val result = json.decodeFromString(ContentSourceSerializer, """{"file":"f.txt"}""") as ContentSource.LocalFile
        assertNull(result.auth)
        assertNull(result.resolution)
    }
}
