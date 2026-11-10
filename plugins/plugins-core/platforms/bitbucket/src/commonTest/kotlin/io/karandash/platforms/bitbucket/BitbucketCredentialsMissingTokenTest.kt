package io.karandash.platforms.bitbucket

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Token is optional at init; the API root comes from `url` (alias `baseUrl`), else `BITBUCKET_API_URL`. */
class BitbucketCredentialsMissingTokenTest {
    private val proxy = mapOf(BitbucketCredentials.API_URL to "https://proxy.example.com/2.0/")

    @Test
    fun `missing token resolves anonymously`() {
        assertNull(BitbucketCredentials.resolve(JsonObject(emptyMap()), { null }).token)
    }

    @Test
    fun `BITBUCKET_API_URL applies when no url is configured`() {
        assertEquals("https://proxy.example.com/2.0", BitbucketCredentials.resolve(JsonObject(emptyMap()), proxy::get).baseUrl)
        assertEquals(BitbucketConfig.DEFAULT_URL, BitbucketCredentials.resolve(JsonObject(emptyMap()), { null }).baseUrl)
    }

    @Test
    fun `configured url or its baseUrl alias beats the env var`() {
        val alias = JsonObject(mapOf("baseUrl" to JsonPrimitive("https://alias.example.com")))
        val url = JsonObject(mapOf("url" to JsonPrimitive("https://url.example.com")))
        assertEquals("https://alias.example.com", BitbucketCredentials.resolve(alias, proxy::get).baseUrl)
        assertEquals("https://url.example.com", BitbucketCredentials.resolve(url, proxy::get).baseUrl)
    }
}
