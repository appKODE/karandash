package io.karandash.cli.templates

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Custom categories merge registrations, survive initialization and answer nothing for unknown names. */
class PresetRegistryCustomCategoryTest {
    @BeforeTest
    fun setup() = PresetRegistry.reset()

    @AfterTest
    fun cleanup() = PresetRegistry.reset()

    @Test
    fun `registrations to one category merge and survive initialize`() {
        PresetRegistry.registerCategory("team", mapOf("a" to mapOf("k" to 1)))
        PresetRegistry.registerCategory("team", mapOf("b" to mapOf("k" to 2)))
        PresetRegistry.initialize()
        PresetRegistry.initialize()
        assertEquals(listOf("a", "b"), PresetRegistry.listCustomTemplates("team"))
        assertEquals(mapOf("k" to 2), PresetRegistry.getCustomTemplate("team", "b"))
    }

    @Test
    fun `unknown categories and names answer nothing`() {
        assertTrue(PresetRegistry.listCustomTemplates("nope").isEmpty())
        assertNull(PresetRegistry.getCustomTemplate("nope", "a"))
        PresetRegistry.registerCategory("team", emptyMap())
        assertNull(PresetRegistry.getCustomTemplate("team", "a"))
        assertNull(PresetRegistry.getBaseTemplate("does-not-exist"))
        assertNull(PresetRegistry.getPlatformTemplate("does-not-exist"))
    }
}
