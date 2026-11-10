package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.http.HttpAuth
import io.karandash.manifest.ConfigSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for HttpAuth sealed class and ConfigSource data class.
 * Verifies construction, credential redaction in toString(), and equality.
 */
class ExtendsAuthParserTest {
    // --- BearerToken ---

    @Test
    fun `BearerToken construction stores token`() {
        val auth = HttpAuth.Bearer(token = "ghp_abc123")
        assertEquals("ghp_abc123", auth.token)
    }

    @Test
    fun `BearerToken toString redacts credentials`() {
        val auth = HttpAuth.Bearer(token = "ghp_abc123")
        val str = auth.toString()
        assertEquals("Bearer(token=***)", str)
        assertFalse(str.contains("ghp_abc123"), "Token should be redacted")
    }

    @Test
    fun `BearerToken equality works`() {
        val a = HttpAuth.Bearer(token = "token1")
        val b = HttpAuth.Bearer(token = "token1")
        val c = HttpAuth.Bearer(token = "token2")
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    // --- BasicAuth ---

    @Test
    fun `BasicAuth construction stores username and password`() {
        val auth = HttpAuth.Basic(username = "user", password = "pass123")
        assertEquals("user", auth.username)
        assertEquals("pass123", auth.password)
    }

    @Test
    fun `BasicAuth toString redacts password but shows username`() {
        val auth = HttpAuth.Basic(username = "deploy-bot", password = "secret-pass")
        val str = auth.toString()
        assertEquals("Basic(username=deploy-bot, password=***)", str)
        assertFalse(str.contains("secret-pass"), "Password should be redacted")
        assertTrue(str.contains("deploy-bot"), "Username should be visible")
    }

    @Test
    fun `BasicAuth equality works`() {
        val a = HttpAuth.Basic(username = "u", password = "p")
        val b = HttpAuth.Basic(username = "u", password = "p")
        val c = HttpAuth.Basic(username = "u", password = "different")
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    // --- CustomHeader ---

    @Test
    fun `CustomHeader construction stores name and value`() {
        val auth = HttpAuth.CustomHeader(name = "X-Api-Key", value = "key-123")
        assertEquals("X-Api-Key", auth.name)
        assertEquals("key-123", auth.value)
    }

    @Test
    fun `CustomHeader toString redacts value but shows header name`() {
        val auth = HttpAuth.CustomHeader(name = "X-Api-Key", value = "secret-key-value")
        val str = auth.toString()
        assertEquals("CustomHeader(name=X-Api-Key, value=***)", str)
        assertFalse(str.contains("secret-key-value"), "Header value should be redacted")
        assertTrue(str.contains("X-Api-Key"), "Header name should be visible")
    }

    @Test
    fun `CustomHeader equality works`() {
        val a = HttpAuth.CustomHeader(name = "X-Key", value = "v1")
        val b = HttpAuth.CustomHeader(name = "X-Key", value = "v1")
        val c = HttpAuth.CustomHeader(name = "X-Key", value = "v2")
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    // --- ConfigSource ---

    @Test
    fun `ConfigSource construction without auth`() {
        val source = ConfigSource(url = "https://example.com/config.yaml")
        assertEquals("https://example.com/config.yaml", source.url)
        assertNull(source.auth)
    }

    @Test
    fun `ConfigSource construction with bearer auth`() {
        val auth = HttpAuth.Bearer(token = "my-token")
        val source = ConfigSource(url = "https://example.com/config.yaml", auth = auth)
        assertEquals("https://example.com/config.yaml", source.url)
        assertEquals(auth, source.auth)
    }

    @Test
    fun `ConfigSource construction with basic auth`() {
        val auth = HttpAuth.Basic(username = "user", password = "pass")
        val source = ConfigSource(url = "https://example.com/config.yaml", auth = auth)
        assertEquals("https://example.com/config.yaml", source.url)
        assertEquals(auth, source.auth)
    }

    @Test
    fun `ConfigSource construction with custom header auth`() {
        val auth = HttpAuth.CustomHeader(name = "X-Token", value = "abc")
        val source = ConfigSource(url = "https://example.com/config.yaml", auth = auth)
        assertEquals("https://example.com/config.yaml", source.url)
        assertEquals(auth, source.auth)
    }

    @Test
    fun `ConfigSource equality works`() {
        val auth = HttpAuth.Bearer(token = "t")
        val a = ConfigSource(url = "https://a.com/c.yaml", auth = auth)
        val b = ConfigSource(url = "https://a.com/c.yaml", auth = auth)
        val c = ConfigSource(url = "https://b.com/c.yaml", auth = auth)
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `ConfigSource default auth is null`() {
        val source = ConfigSource("https://example.com/config.yaml")
        assertNull(source.auth)
    }

    // --- fromJsonObject ---

    private val identity: (String) -> String = { it }

    private fun jsonObj(vararg pairs: Pair<String, String>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    @Test
    fun `fromJsonObject returns null for null data`() {
        assertNull(ExtendsAuthParser.fromJsonObject(null, "[E0000]", "[E0000]", identity))
    }

    @Test
    fun `fromJsonObject parses bearer-token`() {
        val data = jsonObj("type" to "bearer-token", "token" to "my-token")
        val result = ExtendsAuthParser.fromJsonObject(data, "[E0000]", "[E0000]", identity)
        assertEquals(HttpAuth.Bearer("my-token"), result)
    }

    @Test
    fun `fromJsonObject parses basic-auth`() {
        val data = jsonObj("type" to "basic-auth", "username" to "user", "password" to "pass")
        val result = ExtendsAuthParser.fromJsonObject(data, "[E0000]", "[E0000]", identity)
        assertEquals(HttpAuth.Basic("user", "pass"), result)
    }

    @Test
    fun `fromJsonObject parses custom-header`() {
        val data = jsonObj("type" to "custom-header", "name" to "X-Key", "value" to "secret")
        val result = ExtendsAuthParser.fromJsonObject(data, "[E0000]", "[E0000]", identity)
        assertEquals(HttpAuth.CustomHeader("X-Key", "secret"), result)
    }

    @Test
    fun `fromJsonObject throws on missing type`() {
        val data = jsonObj("token" to "abc")
        val ex = assertFailsWith<ConfigException> {
            ExtendsAuthParser.fromJsonObject(data, "[E0100]", "[E0100]", identity)
        }
        assertTrue(ex.message!!.contains("[E0100]"))
    }

    @Test
    fun `fromJsonObject throws on unknown type`() {
        val data = jsonObj("type" to "oauth2")
        val ex = assertFailsWith<ConfigException> {
            ExtendsAuthParser.fromJsonObject(data, "[E0100]", "[E0100]", identity)
        }
        assertTrue(ex.message!!.contains("oauth2"))
    }

    @Test
    fun `fromJsonObject throws on missing required field`() {
        val data = jsonObj("type" to "bearer-token")
        val ex = assertFailsWith<ConfigException> {
            ExtendsAuthParser.fromJsonObject(data, "[E0100]", "[E0100]", identity)
        }
        assertTrue(ex.message!!.contains("[E0100]"))
    }

    @Test
    fun `fromJsonObject applies interpolation`() {
        val data = jsonObj("type" to "bearer-token", "token" to "\${MY_TOKEN}")
        val result = ExtendsAuthParser.fromJsonObject(data, "[E0000]", "[E0000]") { "resolved-value" }
        assertEquals(HttpAuth.Bearer("resolved-value"), result)
    }
}
