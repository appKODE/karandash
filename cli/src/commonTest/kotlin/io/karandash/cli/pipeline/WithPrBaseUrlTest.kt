package io.karandash.cli.pipeline

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class WithPrBaseUrlTest {
    private val prHost = "https://git.other-host.test"

    @Test
    fun `no platforms block takes the url from the pr host`() {
        assertEquals(JsonObject(mapOf("url" to JsonPrimitive(prHost))), withPrBaseUrl(JsonObject(emptyMap()), prHost))
    }

    @Test
    fun `an explicit url wins over the pr host`() {
        val configured = JsonObject(mapOf("url" to JsonPrimitive("https://configured.test")))
        assertEquals(configured, withPrBaseUrl(configured, prHost))
    }

    @Test
    fun `an explicit base url wins over the pr host`() {
        val configured = JsonObject(mapOf("baseUrl" to JsonPrimitive("https://configured.test")))
        assertEquals(configured, withPrBaseUrl(configured, prHost))
    }

    @Test
    fun `no pr url leaves the config alone`() {
        val configured = JsonObject(mapOf("token" to JsonPrimitive("x")))
        assertEquals(configured, withPrBaseUrl(configured, null))
    }
}
