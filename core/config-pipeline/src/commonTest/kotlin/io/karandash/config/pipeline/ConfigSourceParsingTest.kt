@file:Suppress("USELESS_CAST")

package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.http.HttpAuth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for ConfigMapParser.getExtendsSource() parsing logic.
 * Validates both plain string and object-form extends with auth.
 */
class ConfigSourceParsingTest {
    // --- Plain string form ---

    @Test
    fun `plain string extends produces ConfigSource with null auth`() {
        val data = mapOf<String, Any?>("extends" to "https://example.com/parent.yaml")
        val sources = ConfigMapParser.getExtendsSources(data)

        assertEquals(1, sources.size)
        val source = sources.first()
        assertEquals("https://example.com/parent.yaml", source.url)
        assertNull(source.auth)
    }

    @Test
    fun `null extends produces null ConfigSource`() {
        val data = mapOf<String, Any?>("rules" to mapOf("titleFormat" to mapOf("pattern" to "^feat:.*")))
        val sources = ConfigMapParser.getExtendsSources(data)

        assertTrue(sources.isEmpty())
    }

    @Test
    fun `plain string extends with env var interpolation`() {
        // Env var interpolation: with denylist approach, unset non-denylisted vars resolve to empty
        val data = mapOf<String, Any?>("extends" to "https://\${HOST}/parent.yaml")
        val sources = ConfigMapParser.getExtendsSources(data)

        assertEquals(1, sources.size)
        val source = sources.first()
        val envHost = io.karandash.platform.PlatformEnvironment
            .getEnv("HOST")
        if (envHost != null) {
            assertTrue(source.url.contains(envHost))
        } else {
            // Unset non-denylisted vars resolve to empty string
            assertEquals("https:///parent.yaml", source.url)
        }
    }

    // --- Bearer-token object form ---

    @Test
    fun `bearer-token auth object form`() {
        val data = mapOf<String, Any?>(
            "extends" to mapOf(
                "url" to "https://gitlab.com/api/v4/projects/123/repository/files/.karandash.yaml/raw",
                "auth" to mapOf(
                    "type" to "bearer-token",
                    "token" to "glpat-xxxxxxxxxxxx"
                )
            )
        )
        val sources = ConfigMapParser.getExtendsSources(data)
        val source = sources.first()

        assertNotNull(source)
        assertEquals(
            "https://gitlab.com/api/v4/projects/123/repository/files/.karandash.yaml/raw",
            source.url
        )
        assertTrue(source.auth is HttpAuth.Bearer)
        assertEquals("glpat-xxxxxxxxxxxx", (source.auth as HttpAuth.Bearer).token)
    }

    @Test
    fun `bearer-token with env var interpolation in token`() {
        val data = mapOf<String, Any?>(
            "extends" to mapOf(
                "url" to "https://example.com/config.yaml",
                "auth" to mapOf(
                    "type" to "bearer-token",
                    "token" to "\${UNSET_TOKEN_VAR}"
                )
            )
        )
        val sources = ConfigMapParser.getExtendsSources(data)
        val source = sources.first()

        assertNotNull(source)
        assertTrue(source.auth is HttpAuth.Bearer)
        // With denylist approach, unset non-denylisted vars resolve to empty string
        assertEquals("", (source.auth as HttpAuth.Bearer).token)
    }

    // --- Basic-auth object form ---

    @Test
    fun `basic-auth object form`() {
        val data = mapOf<String, Any?>(
            "extends" to mapOf(
                "url" to "https://bitbucket.org/team/repo/raw/HEAD/.karandash.yaml",
                "auth" to mapOf(
                    "type" to "basic-auth",
                    "username" to "deploy-bot",
                    "password" to "secret-pass"
                )
            )
        )
        val sources = ConfigMapParser.getExtendsSources(data)
        val source = sources.first()

        assertNotNull(source)
        assertTrue(source.auth is HttpAuth.Basic)
        val basicAuth = source.auth as HttpAuth.Basic
        assertEquals("deploy-bot", basicAuth.username)
        assertEquals("secret-pass", basicAuth.password)
    }

    @Test
    fun `basic-auth with env var interpolation`() {
        val data = mapOf<String, Any?>(
            "extends" to mapOf(
                "url" to "https://example.com/config.yaml",
                "auth" to mapOf(
                    "type" to "basic-auth",
                    "username" to "\${UNSET_USER_VAR}",
                    "password" to "\${UNSET_PASS_VAR}"
                )
            )
        )
        val sources = ConfigMapParser.getExtendsSources(data)
        val source = sources.first()

        assertNotNull(source)
        assertTrue(source.auth is HttpAuth.Basic)
        val basicAuth = source.auth as HttpAuth.Basic
        // With denylist approach, unset non-denylisted vars resolve to empty string
        assertEquals("", basicAuth.username)
        assertEquals("", basicAuth.password)
    }

    // --- Custom-header object form ---

    @Test
    fun `custom-header object form`() {
        val data = mapOf<String, Any?>(
            "extends" to mapOf(
                "url" to "https://internal.company.com/configs/karandash.yaml",
                "auth" to mapOf(
                    "type" to "custom-header",
                    "name" to "X-Api-Key",
                    "value" to "key-abc123"
                )
            )
        )
        val sources = ConfigMapParser.getExtendsSources(data)
        val source = sources.first()

        assertNotNull(source)
        assertTrue(source.auth is HttpAuth.CustomHeader)
        val customAuth = source.auth as HttpAuth.CustomHeader
        assertEquals("X-Api-Key", customAuth.name)
        assertEquals("key-abc123", customAuth.value)
    }

    // --- Object form without auth ---

    @Test
    fun `object form without auth produces ConfigSource with null auth`() {
        val data = mapOf<String, Any?>(
            "extends" to mapOf(
                "url" to "https://example.com/parent.yaml"
            )
        )
        val sources = ConfigMapParser.getExtendsSources(data)
        val source = sources.first()

        assertNotNull(source)
        assertEquals("https://example.com/parent.yaml", source.url)
        assertNull(source.auth)
    }

    // --- Error cases ---

    @Test
    fun `missing url in object form throws E0004`() {
        val data = mapOf<String, Any?>(
            "extends" to mapOf(
                "auth" to mapOf(
                    "type" to "bearer-token",
                    "token" to "some-token"
                )
            )
        )

        val exception = assertFailsWith<ConfigException> {
            ConfigMapParser.getExtendsSources(data)
        }
        assertTrue(exception.message!!.contains("E0004"))
        assertTrue(exception.message!!.contains("url"))
    }

    @Test
    fun `missing type in auth throws E0004`() {
        val data = mapOf<String, Any?>(
            "extends" to mapOf(
                "url" to "https://example.com/config.yaml",
                "auth" to mapOf(
                    "token" to "some-token"
                )
            )
        )

        val exception = assertFailsWith<ConfigException> {
            ConfigMapParser.getExtendsSources(data)
        }
        assertTrue(exception.message!!.contains("E0004"))
        assertTrue(exception.message!!.contains("type"))
    }

    @Test
    fun `invalid auth type throws E0008`() {
        val data = mapOf<String, Any?>(
            "extends" to mapOf(
                "url" to "https://example.com/config.yaml",
                "auth" to mapOf(
                    "type" to "oauth2-flow",
                    "token" to "some-token"
                )
            )
        )

        val exception = assertFailsWith<ConfigException> {
            ConfigMapParser.getExtendsSources(data)
        }
        assertTrue(exception.message!!.contains("E0008"))
        assertTrue(exception.message!!.contains("oauth2-flow"))
    }

    @Test
    fun `missing required token field for bearer-token throws E0004`() {
        val data = mapOf<String, Any?>(
            "extends" to mapOf(
                "url" to "https://example.com/config.yaml",
                "auth" to mapOf(
                    "type" to "bearer-token"
                    // missing 'token'
                )
            )
        )

        val exception = assertFailsWith<ConfigException> {
            ConfigMapParser.getExtendsSources(data)
        }
        assertTrue(exception.message!!.contains("E0004"))
        assertTrue(exception.message!!.contains("token"))
    }

    @Test
    fun `missing required username field for basic-auth throws E0004`() {
        val data = mapOf<String, Any?>(
            "extends" to mapOf(
                "url" to "https://example.com/config.yaml",
                "auth" to mapOf(
                    "type" to "basic-auth",
                    "password" to "pass"
                    // missing 'username'
                )
            )
        )

        val exception = assertFailsWith<ConfigException> {
            ConfigMapParser.getExtendsSources(data)
        }
        assertTrue(exception.message!!.contains("E0004"))
        assertTrue(exception.message!!.contains("username"))
    }

    @Test
    fun `missing required password field for basic-auth throws E0004`() {
        val data = mapOf<String, Any?>(
            "extends" to mapOf(
                "url" to "https://example.com/config.yaml",
                "auth" to mapOf(
                    "type" to "basic-auth",
                    "username" to "user"
                    // missing 'password'
                )
            )
        )

        val exception = assertFailsWith<ConfigException> {
            ConfigMapParser.getExtendsSources(data)
        }
        assertTrue(exception.message!!.contains("E0004"))
        assertTrue(exception.message!!.contains("password"))
    }

    @Test
    fun `missing required name field for custom-header throws E0004`() {
        val data = mapOf<String, Any?>(
            "extends" to mapOf(
                "url" to "https://example.com/config.yaml",
                "auth" to mapOf(
                    "type" to "custom-header",
                    "value" to "v"
                    // missing 'name'
                )
            )
        )

        val exception = assertFailsWith<ConfigException> {
            ConfigMapParser.getExtendsSources(data)
        }
        assertTrue(exception.message!!.contains("E0004"))
        assertTrue(exception.message!!.contains("name"))
    }

    @Test
    fun `missing required value field for custom-header throws E0004`() {
        val data = mapOf<String, Any?>(
            "extends" to mapOf(
                "url" to "https://example.com/config.yaml",
                "auth" to mapOf(
                    "type" to "custom-header",
                    "name" to "X-Key"
                    // missing 'value'
                )
            )
        )

        val exception = assertFailsWith<ConfigException> {
            ConfigMapParser.getExtendsSources(data)
        }
        assertTrue(exception.message!!.contains("E0004"))
        assertTrue(exception.message!!.contains("value"))
    }

    @Test
    fun `invalid extends value type throws E0008`() {
        val data = mapOf<String, Any?>("extends" to 42)

        val exception = assertFailsWith<ConfigException> {
            ConfigMapParser.getExtendsSources(data)
        }
        assertTrue(exception.message!!.contains("E0008"))
    }

    // --- Array syntax tests ---

    @Test
    fun `should parse extends array of strings`() {
        val data = mapOf<String, Any?>(
            "extends" to listOf(
                "https://example.com/base.yaml",
                "https://example.com/security.yaml"
            )
        )
        val sources = ConfigMapParser.getExtendsSources(data)

        assertEquals(2, sources.size)
        assertEquals("https://example.com/base.yaml", sources[0].url)
        assertNull(sources[0].auth)
        assertEquals("https://example.com/security.yaml", sources[1].url)
        assertNull(sources[1].auth)
    }

    @Test
    fun `should parse extends array with mixed types`() {
        val data = mapOf<String, Any?>(
            "extends" to listOf(
                "https://example.com/base.yaml",
                mapOf(
                    "url" to "https://private.com/config.yaml",
                    "auth" to mapOf("type" to "bearer-token", "token" to "secret123")
                )
            )
        )
        val sources = ConfigMapParser.getExtendsSources(data)

        assertEquals(2, sources.size)
        assertEquals("https://example.com/base.yaml", sources[0].url)
        assertNull(sources[0].auth)
        assertEquals("https://private.com/config.yaml", sources[1].url)
        assertTrue(sources[1].auth is HttpAuth.Bearer)
        assertEquals("secret123", (sources[1].auth as HttpAuth.Bearer).token)
    }

    @Test
    fun `should parse empty extends array`() {
        val data = mapOf<String, Any?>("extends" to emptyList<Any>())
        val sources = ConfigMapParser.getExtendsSources(data)

        assertTrue(sources.isEmpty())
    }

    @Test
    fun `should throw E0014 for too many extends entries`() {
        val data = mapOf<String, Any?>(
            "extends" to List(11) { "https://example.com/config$it.yaml" }
        )

        val exception = assertFailsWith<ConfigException> {
            ConfigMapParser.getExtendsSources(data)
        }
        assertTrue(exception.message!!.contains("E0014"))
        assertTrue(exception.message!!.contains("11"))
        assertTrue(exception.message!!.contains("10"))
    }

    @Test
    fun `should throw E0008 for invalid extends array entry`() {
        val data = mapOf<String, Any?>(
            "extends" to listOf("https://example.com/base.yaml", 123)
        )

        val exception = assertFailsWith<ConfigException> {
            ConfigMapParser.getExtendsSources(data)
        }
        assertTrue(exception.message!!.contains("E0008"))
        assertTrue(exception.message!!.contains("index 1"))
    }
}
