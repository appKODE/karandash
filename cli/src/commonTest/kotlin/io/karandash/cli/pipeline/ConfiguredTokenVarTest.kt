package io.karandash.cli.pipeline

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConfiguredTokenVarTest {
    private fun layer(token: String): Pair<String, JsonElement> =
        "cfg" to JsonObject(mapOf("platforms" to JsonObject(mapOf("gitlab" to JsonObject(mapOf("token" to JsonPrimitive(token)))))))

    @Test
    fun `env reference token names the configured variable`() {
        assertEquals("GITLAB_MY_TOKEN", configuredTokenVar(listOf(layer("\${GITLAB_MY_TOKEN}")), "gitlab"))
    }

    @Test
    fun `literal token names no variable`() {
        assertNull(configuredTokenVar(listOf(layer("glpat-literal")), "gitlab"))
    }
}
