package io.karandash.config

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Behaviour of the `platforms` key: a platform service reads its own block by `configKey`. */
class PlatformsKeyBehaviourTest {
    @Test
    fun `platforms block is delivered to the service with that configKey in any spelling`() {
        val block = JsonObject(mapOf("url" to JsonPrimitive("https://dev.azure.com/x")))
        val config = Config(platforms = mapOf("azure-devops" to block, "github" to JsonObject(emptyMap())))

        assertEquals(block, config.platformConfig("azureDevOps"))
        assertNull(config.platformConfig("gitea"))
    }
}
