package io.karandash.config.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigMapParserPatternsTest {
    @Test
    fun `parseFullConfig extracts patterns block into Config patterns map`() {
        val raw = mapOf(
            "patterns" to mapOf(
                "project-ticket" to "@jira-ticket(@project:MAPS)",
                "project-title" to "@optional(@project-ticket) .+",
            ),
        )
        val config = ConfigMapParser.parseFullConfig(raw).config

        assertEquals(2, config.patterns.size)
        // Pattern names stay as-is (not normalized to camelCase) since they are
        // user-defined identifiers referenced as @project-ticket, not config keys.
        assertEquals("@jira-ticket(@project:MAPS)", config.patterns["project-ticket"])
        assertEquals("@optional(@project-ticket) .+", config.patterns["project-title"])
    }

    @Test
    fun `parseFullConfig returns empty map when no patterns block present`() {
        val raw = mapOf(
            "rules" to mapOf(
                "title-format" to mapOf("min-length" to 10),
            ),
        )
        val config = ConfigMapParser.parseFullConfig(raw).config

        assertTrue(config.patterns.isEmpty())
    }

    @Test
    fun `parseFullConfig patterns block with env var reference interpolates value`() {
        val raw = mapOf(
            "patterns" to mapOf(
                "my-ticket" to "@jira-ticket(@project:\${MY_KEY})",
            ),
        )
        // Defaults are passed by the config chain after extracting the env: block
        val defaults = mapOf("MY_KEY" to "MAPS")
        val config = ConfigMapParser.parseFullConfig(raw, defaults).config

        // Env var in the value is interpolated; key stays as-is
        assertEquals("@jira-ticket(@project:MAPS)", config.patterns["my-ticket"])
    }

    @Test
    fun `a pattern value is interpolated once so a placeholder inside an env value stays literal`() {
        val raw = mapOf(
            "patterns" to mapOf("my-ticket" to "\${KARANDASH_NEW_B8_OUTER}"),
        )
        val defaults = mapOf(
            "KARANDASH_NEW_B8_OUTER" to "\${KARANDASH_NEW_B8_INNER}",
            "KARANDASH_NEW_B8_INNER" to "expanded-twice",
        )
        val config = ConfigMapParser.parseFullConfig(raw, defaults).config

        assertEquals("\${KARANDASH_NEW_B8_INNER}", config.patterns["my-ticket"])
    }

    @Test
    fun `parseFullConfig patterns coexist with rules and other blocks`() {
        val raw = mapOf(
            "patterns" to mapOf(
                "project-title" to ".+",
            ),
            "rules" to mapOf(
                "title-format" to mapOf("pattern" to "@project-title", "min-length" to 5),
            ),
        )
        val config = ConfigMapParser.parseFullConfig(raw).config

        assertEquals(1, config.patterns.size)
        assertEquals(".+", config.patterns["project-title"])
        assertTrue(config.rules.ruleConfigs.containsKey("titleFormat"))
    }
}
