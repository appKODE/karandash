package io.karandash.cli.plugin

import io.karandash.registry.RegistryAuth
import kotlin.test.Test
import kotlin.test.assertEquals

class RegistryAuthTest {
    @Test
    fun default_header_produces_bearer_authorization() {
        val auth = RegistryAuth(token = "my-token")

        val (name, value) = auth.toHeader()

        assertEquals("Authorization", name)
        assertEquals("Bearer my-token", value)
    }

    @Test
    fun gitlab_header_produces_private_token_without_bearer_prefix() {
        val auth = RegistryAuth(token = "glpat-xyz", headerName = RegistryAuth.GITLAB_HEADER_NAME)

        val (name, value) = auth.toHeader()

        assertEquals("PRIVATE-TOKEN", name)
        assertEquals("glpat-xyz", value)
    }

    @Test
    fun custom_header_name_uses_token_directly() {
        val auth = RegistryAuth(token = "secret", headerName = "X-API-Key")

        val (name, value) = auth.toHeader()

        assertEquals("X-API-Key", name)
        assertEquals("secret", value)
    }

    @Test
    fun companion_constants_have_expected_values() {
        assertEquals("Authorization", RegistryAuth.DEFAULT_HEADER_NAME)
        assertEquals("PRIVATE-TOKEN", RegistryAuth.GITLAB_HEADER_NAME)
    }
}
