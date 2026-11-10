package io.karandash.sdk.content

import io.karandash.duration.KarandashDuration
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ContentSourceSerializerTest {
    private val json = KarandashJson.Default

    private fun content(text: String): ContentSource = json.decodeFromString(ContentSource.serializer(), text)

    private fun directory(text: String): DirectorySource = json.decodeFromString(DirectorySource.serializer(), text)

    @Test
    fun `content sources decode from every supported shape`() {
        val cases = mapOf(
            "\"hello\"" to ContentSource.Inline("hello"),
            "42" to ContentSource.Inline("42"),
            """{"inline":"x"}""" to ContentSource.Inline("x"),
            """{"file":"a.md"}""" to ContentSource.LocalFile("a.md"),
            """{"file":"a.md","auth":"t","resolution":"repo"}""" to ContentSource.LocalFile("a.md", "t", "repo"),
            """{"url":"https://h/x"}""" to ContentSource.RemoteFile("https://h/x"),
            """{"url":"https://h/x","auth":"t","cache-ttl":"90"}""" to
                ContentSource.RemoteFile("https://h/x", "t", KarandashDuration.ofSeconds(90)),
            """{"url":"u","cache-ttl":"5m"}""" to ContentSource.RemoteFile("u", cacheTtl = KarandashDuration.ofMinutes(5)),
            """{"repo-file":"docs/a.md"}""" to ContentSource.RepoFile("docs/a.md"),
            """{"repo-file":"docs/a.md","ref":"main"}""" to ContentSource.RepoFile("docs/a.md", "main"),
            """["a",{"file":"b"}]""" to ContentSource.Multiple(listOf(ContentSource.Inline("a"), ContentSource.LocalFile("b"))),
        )
        cases.forEach { (text, expected) -> assertEquals(expected, content(text), text) }
    }

    @Test
    fun `content sources survive an encode decode round trip`() {
        val sources = listOf(
            ContentSource.Inline("x"),
            ContentSource.LocalFile("a.md"),
            ContentSource.LocalFile("a.md", "t", "repo"),
            ContentSource.RemoteFile("u"),
            ContentSource.RemoteFile("u", "t", KarandashDuration.ofMinutes(5)),
            ContentSource.RepoFile("p"),
            ContentSource.RepoFile("p", "dev"),
            ContentSource.Multiple(listOf(ContentSource.Inline("a"), ContentSource.RepoFile("p", "dev"))),
        )
        sources.forEach { source ->
            val text = json.encodeToString(ContentSource.serializer(), source)
            assertEquals(source, content(text), text)
        }
    }

    @Test
    fun `content sources reject null and unknown objects`() {
        listOf("null", """{"other":1}""", "[null]").forEach { text ->
            assertFailsWith<SerializationException>(text) { content(text) }
        }
    }

    @Test
    fun `directory sources decode from every supported shape`() {
        val cases = mapOf(
            "\"rules\"" to DirectorySource.LocalDir("rules"),
            """{"path":"rules"}""" to DirectorySource.LocalDir("rules"),
            """{"url":"https://h/d"}""" to DirectorySource.RemoteDir("https://h/d"),
            """{"url":"u","auth":"t","cache-ttl":"60"}""" to DirectorySource.RemoteDir("u", "t", KarandashDuration.ofSeconds(60)),
            """{"url":"u","cache-ttl":"1h"}""" to DirectorySource.RemoteDir("u", cacheTtl = KarandashDuration.ofHours(1)),
            """{"repo-dir":"d"}""" to DirectorySource.RepoDir("d"),
            """{"repo-dir":"d","ref":"v1"}""" to DirectorySource.RepoDir("d", "v1"),
        )
        cases.forEach { (text, expected) -> assertEquals(expected, directory(text), text) }
    }

    @Test
    fun `directory sources survive an encode decode round trip`() {
        val sources = listOf(
            DirectorySource.LocalDir("d"),
            DirectorySource.RemoteDir("u"),
            DirectorySource.RemoteDir("u", "t", KarandashDuration.ofMinutes(5)),
            DirectorySource.RepoDir("d"),
            DirectorySource.RepoDir("d", "v1"),
        )
        sources.forEach { source ->
            val text = json.encodeToString(DirectorySource.serializer(), source)
            assertEquals(source, directory(text), text)
        }
    }

    @Test
    fun `directory sources reject null arrays and unknown objects`() {
        listOf("null", "[\"a\"]", """{"other":1}""").forEach { text ->
            assertFailsWith<SerializationException>(text) { directory(text) }
        }
    }

    @Test
    fun `variant data classes compare by every field`() {
        assertNotEquals(ContentSource.LocalFile("a"), ContentSource.LocalFile("a", auth = "t"))
        assertNotEquals(ContentSource.RemoteFile("u"), ContentSource.RemoteFile("u", cacheTtl = KarandashDuration.ofSeconds(1)))
        assertNotEquals(ContentSource.Multiple(emptyList()), ContentSource.Multiple(emptyList(), separator = "-"))
        assertEquals("\n\n", ContentSource.Multiple(emptyList()).separator)
        assertNotEquals(DirectorySource.RepoDir("d"), DirectorySource.RepoDir("d", "v"))
    }
}
