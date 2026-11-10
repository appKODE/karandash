package io.karandash.config

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `platforms.<key>` blocks are found for a service's configKey whatever naming convention the user wrote. */
class PlatformConfigLookupTest {
    private val block = JsonObject(mapOf("url" to JsonPrimitive("http://localhost/ado")))

    @Test
    fun kebabCaseKeyNormalizedToCamelCaseFindsTheMixedCaseConfigKey() {
        // `azure-devops:` is normalized to `azureDevops`; the service's configKey is `azureDevOps`.
        val config = Config(platforms = mapOf("azureDevops" to block))
        assertEquals(block, config.platformConfig("azureDevOps"))
    }

    @Test
    fun exactKeyWinsAndMissingKeyIsNull() {
        val other = JsonObject(emptyMap())
        val config = Config(platforms = mapOf("azureDevOps" to block, "azuredevops" to other))
        assertEquals(block, config.platformConfig("azureDevOps"))
        assertNull(config.platformConfig("gitea"))
        assertNull(Config().platformConfig("azureDevOps"))
    }
}
