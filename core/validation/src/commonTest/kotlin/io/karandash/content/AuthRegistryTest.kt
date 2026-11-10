package io.karandash.content

import io.karandash.config.AuthConfig
import io.karandash.config.AuthType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthRegistryTest {
    private val tokenConfig = AuthConfig(type = AuthType.TOKEN, token = "tok-secret")
    private val basicConfig = AuthConfig(type = AuthType.BASIC, username = "user", password = "pass")

    private val registry = AuthRegistry(
        mapOf(
            "my-token" to tokenConfig,
            "my-basic" to basicConfig,
        ),
    )

    @Test
    fun resolveReturnsCorrectConfig() {
        assertEquals(tokenConfig, registry.resolve("my-token"))
        assertEquals(basicConfig, registry.resolve("my-basic"))
    }

    @Test
    fun resolveWithUnknownNameThrowsWithAvailableNames() {
        val ex = assertFailsWith<IllegalStateException> {
            registry.resolve("missing-auth")
        }
        assertTrue(ex.message?.contains("missing-auth") == true)
        assertTrue(ex.message?.contains("my-token") == true || ex.message?.contains("my-basic") == true)
    }

    @Test
    fun hasTrueForKnownName() {
        assertTrue(registry.has("my-token"))
        assertTrue(registry.has("my-basic"))
    }

    @Test
    fun hasFalseForUnknownName() {
        assertFalse(registry.has("nope"))
    }

    @Test
    fun emptyRegistryHasNoConfigs() {
        val empty = AuthRegistry.EMPTY
        assertFalse(empty.has("anything"))
        assertFailsWith<IllegalStateException> {
            empty.resolve("anything")
        }
    }
}
