package io.karandash.config.pipeline

import io.karandash.api.exception.ConfigParseException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigMapParserDeadKeysTest {
    /** `options:` in YAML/JSON works as it does in KTS: its keys apply at the top level. */
    @Test
    fun `options block applies like top level keys`() {
        val config = ConfigMapParser
            .parseFullConfig(mapOf("options" to mapOf("dry-run" to true, "allow-localhost" to true)))
            .config

        assertTrue(config.dryRun)
        assertTrue(config.allowLocalhost)
    }

    /** A key set at the top level wins over the same key inside `options:`. */
    @Test
    fun `top level key wins over options`() {
        val config = ConfigMapParser
            .parseFullConfig(mapOf("dry-run" to false, "options" to mapOf("dry-run" to true)))
            .config

        assertEquals(false, config.dryRun)
    }

    /** Every script entry is kept, in order, and a bad entry fails loudly instead of vanishing. */
    @Test
    fun `scripts keep every entry and reject a broken one`() {
        val config = ConfigMapParser
            .parseFullConfig(mapOf("scripts" to listOf("a.kts", mapOf("path" to "b.kts", "mode" to "sandbox"))))
            .config
        assertEquals(listOf("a.kts", "b.kts"), config.scripts?.map { it.path })

        val ex = assertFailsWith<ConfigParseException> {
            ConfigMapParser.parseFullConfig(mapOf("scripts" to listOf("a.kts", mapOf("mode" to "sandbox"))))
        }
        assertTrue(ex.message.orEmpty().contains("scripts[1]"), ex.message)
    }
}
