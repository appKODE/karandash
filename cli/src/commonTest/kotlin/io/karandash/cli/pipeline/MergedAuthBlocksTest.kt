package io.karandash.cli.pipeline

import io.karandash.config.AuthType
import io.karandash.config.pipeline.AuthBlockParser
import io.karandash.sdk.config.anyToJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `auth:` across `extends:`: per host, destination and credentials come from one layer (the highest-priority one
 * setting any of them), other fields merge, and every layer's `env:` defaults still apply.
 */
class MergedAuthBlocksTest {
    private fun layer(vararg keys: Pair<String, Any?>) = anyToJsonElement(mapOf(*keys))

    @Test
    fun `a parent baseUrl is not combined with the root token`() {
        val parent = layer(
            "auth" to mapOf("gitlab" to mapOf("type" to "token", "baseUrl" to "https://attacker.example.com")),
        )
        val root = layer("auth" to mapOf("gitlab" to mapOf("type" to "token", "token" to "root-secret")))

        val gitlab = AuthBlockParser.parse(mergedAuthBlocks(root, listOf("parent.yaml" to parent))).resolve("gitlab")

        assertEquals("root-secret", gitlab.token)
        assertNull(gitlab.baseUrl)
    }

    @Test
    fun `a parent credential is not combined with the root baseUrl`() {
        val parent = layer("auth" to mapOf("gitlab" to mapOf("type" to "basic", "username" to "u", "password" to "parent-secret")))
        val root = layer("auth" to mapOf("gitlab" to mapOf("type" to "token", "baseUrl" to "https://gitlab.example.com")))

        val gitlab = AuthBlockParser.parse(mergedAuthBlocks(root, listOf("parent.yaml" to parent))).resolve("gitlab")

        assertNull(gitlab.password)
        assertNull(gitlab.username)
        assertEquals("https://gitlab.example.com", gitlab.baseUrl)
    }

    @Test
    fun `a parent non-secret field still merges into the root host`() {
        val parent = layer("auth" to mapOf("gitlab" to mapOf("type" to "basic", "fileResolution" to "strict")))
        val root = layer(
            "auth" to mapOf("gitlab" to mapOf("type" to "token", "token" to "child", "baseUrl" to "https://gitlab.example.com")),
        )

        val gitlab = AuthBlockParser.parse(mergedAuthBlocks(root, listOf("parent.yaml" to parent))).resolve("gitlab")

        assertEquals("child", gitlab.token)
        assertEquals("https://gitlab.example.com", gitlab.baseUrl)
        assertEquals("strict", gitlab.fileResolution)
        assertEquals(AuthType.TOKEN, gitlab.type)
    }

    @Test
    fun `a host only a parent defines keeps the parent entry`() {
        val parent = layer(
            "auth" to mapOf("gitlab" to mapOf("type" to "token", "token" to "parent", "baseUrl" to "https://gitlab.example.com")),
        )
        val root = layer("auth" to mapOf("github" to mapOf("type" to "token", "token" to "child")))

        val gitlab = AuthBlockParser.parse(mergedAuthBlocks(root, listOf("parent.yaml" to parent))).resolve("gitlab")

        assertEquals("parent", gitlab.token)
        assertEquals("https://gitlab.example.com", gitlab.baseUrl)
    }

    @Test
    fun `env defaults of every layer interpolate the merged auth and the root wins`() {
        val parent = layer(
            "env" to mapOf("KD_MAB_TOKEN" to "parent-default", "KD_MAB_URL" to "https://parent.example.com"),
            "auth" to mapOf("gitlab" to mapOf("type" to "token", "token" to "\${KD_MAB_TOKEN}", "baseUrl" to "\${KD_MAB_URL}")),
        )
        val root = layer("env" to mapOf("KD_MAB_TOKEN" to "root-default"))

        val gitlab = AuthBlockParser.parse(mergedAuthBlocks(root, listOf("parent.yaml" to parent))).resolve("gitlab")

        assertEquals("root-default", gitlab.token)
        assertEquals("https://parent.example.com", gitlab.baseUrl)
    }
}
