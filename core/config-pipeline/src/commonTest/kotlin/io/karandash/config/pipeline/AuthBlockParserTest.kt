package io.karandash.config.pipeline

import io.karandash.config.AuthType
import io.karandash.content.AuthRegistry
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AuthBlockParserTest {
    @Test
    fun bearer_auth_entry_is_parsed_correctly() {
        val element = buildJsonObject {
            put(
                "auth",
                buildJsonObject {
                    put(
                        "my-gitlab",
                        buildJsonObject {
                            put("type", "bearer")
                            put("token", "secret-token")
                        }
                    )
                }
            )
        }

        val registry = AuthBlockParser.parse(element)

        assertTrue(registry.has("my-gitlab"))
        val config = registry.resolve("my-gitlab")
        assertEquals(AuthType.BEARER, config.type)
        assertEquals("secret-token", config.token)
    }

    @Test
    fun custom_header_auth_entry_is_parsed_correctly() {
        val element = buildJsonObject {
            put(
                "auth",
                buildJsonObject {
                    put(
                        "internal-api",
                        buildJsonObject {
                            put("type", "header")
                            put("name", "X-Api-Key")
                            put("value", "abc123")
                        }
                    )
                }
            )
        }

        val registry = AuthBlockParser.parse(element)

        assertTrue(registry.has("internal-api"))
        val config = registry.resolve("internal-api")
        assertEquals(AuthType.HEADER, config.type)
        assertEquals("X-Api-Key", config.name)
        assertEquals("abc123", config.value)
    }

    @Test
    fun basic_auth_entry_is_parsed_correctly() {
        val element = buildJsonObject {
            put(
                "auth",
                buildJsonObject {
                    put(
                        "nexus",
                        buildJsonObject {
                            put("type", "basic")
                            put("username", "admin")
                            put("password", "pass123")
                        }
                    )
                }
            )
        }

        val registry = AuthBlockParser.parse(element)

        assertTrue(registry.has("nexus"))
        val config = registry.resolve("nexus")
        assertEquals(AuthType.BASIC, config.type)
        assertEquals("admin", config.username)
        assertEquals("pass123", config.password)
    }

    @Test
    fun missing_auth_block_returns_empty_registry() {
        val element = buildJsonObject {
            put("rules", buildJsonObject {})
        }

        val registry = AuthBlockParser.parse(element)

        assertSame(AuthRegistry.EMPTY, registry)
    }

    @Test
    fun empty_auth_block_returns_empty_registry() {
        val element = buildJsonObject {
            put("auth", JsonObject(emptyMap()))
        }

        val registry = AuthBlockParser.parse(element)

        assertSame(AuthRegistry.EMPTY, registry)
    }

    @Test
    fun malformed_entry_is_skipped_but_valid_entries_still_parsed() {
        val element = buildJsonObject {
            put(
                "auth",
                buildJsonObject {
                    // malformed: missing required "type" field
                    put(
                        "bad-entry",
                        buildJsonObject {
                            put("token", "some-token")
                        }
                    )
                    // valid entry
                    put(
                        "good-entry",
                        buildJsonObject {
                            put("type", "bearer")
                            put("token", "valid-token")
                        }
                    )
                }
            )
        }

        val registry = AuthBlockParser.parse(element)

        assertFalse(registry.has("bad-entry"))
        assertTrue(registry.has("good-entry"))
        assertEquals("valid-token", registry.resolve("good-entry").token)
    }

    @Test
    fun non_object_auth_entry_is_skipped() {
        val element = buildJsonObject {
            put(
                "auth",
                buildJsonObject {
                    // non-object entry: should be skipped
                    put("string-entry", JsonPrimitive("not-an-object"))
                    put("null-entry", JsonNull)
                    put(
                        "valid",
                        buildJsonObject {
                            put("type", "bearer")
                            put("token", "tok")
                        }
                    )
                }
            )
        }

        val registry = AuthBlockParser.parse(element)

        assertFalse(registry.has("string-entry"))
        assertFalse(registry.has("null-entry"))
        assertTrue(registry.has("valid"))
    }

    @Test
    fun multiple_valid_entries_are_all_registered() {
        val element = buildJsonObject {
            put(
                "auth",
                buildJsonObject {
                    put(
                        "gitlab-token",
                        buildJsonObject {
                            put("type", "bearer")
                            put("token", "gl-token")
                        }
                    )
                    put(
                        "github-token",
                        buildJsonObject {
                            put("type", "bearer")
                            put("token", "gh-token")
                        }
                    )
                    put(
                        "internal",
                        buildJsonObject {
                            put("type", "basic")
                            put("username", "user")
                            put("password", "pw")
                        }
                    )
                }
            )
        }

        val registry = AuthBlockParser.parse(element)

        assertTrue(registry.has("gitlab-token"))
        assertTrue(registry.has("github-token"))
        assertTrue(registry.has("internal"))
        assertEquals("gl-token", registry.resolve("gitlab-token").token)
        assertEquals("gh-token", registry.resolve("github-token").token)
    }

    @Test
    fun non_json_object_root_returns_empty_registry() {
        val element = JsonPrimitive("not-an-object")

        val registry = AuthBlockParser.parse(element)

        assertSame(AuthRegistry.EMPTY, registry)
    }

    @Test
    fun env_placeholders_in_the_auth_block_are_interpolated_from_env_defaults() {
        val element = buildJsonObject {
            put("env", buildJsonObject { put("KARANDASH_AUTH_TEST_TOKEN_UNSET", "from-env-default") })
            put(
                "auth",
                buildJsonObject {
                    put(
                        "my-gitlab",
                        buildJsonObject {
                            put("type", "bearer")
                            put("token", "\${KARANDASH_AUTH_TEST_TOKEN_UNSET}")
                        },
                    )
                },
            )
        }

        val registry = AuthBlockParser.parse(element)

        assertEquals("from-env-default", registry.resolve("my-gitlab").token)
    }
}
