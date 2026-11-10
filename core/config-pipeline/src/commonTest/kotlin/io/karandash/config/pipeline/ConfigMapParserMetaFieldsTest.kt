package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.config.DryRunScope
import io.karandash.config.RuleExecutionMeta
import io.karandash.config.RuleMode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigMapParserMetaFieldsTest {
    @Test
    fun `parseFullConfig strips priority and bail-on-fail from rule config`() {
        val raw = mapOf(
            "rules" to mapOf(
                "my-rule" to mapOf(
                    "priority" to 1,
                    "bail-on-fail" to true,
                    "some-option" to "value"
                )
            )
        )
        val config = ConfigMapParser.parseFullConfig(raw).config
        val ruleConfig = config.rules.ruleConfigs["myRule"]
        assertNull(ruleConfig?.jsonObject?.get("priority"), "priority should be stripped from rule config")
        assertNull(ruleConfig?.jsonObject?.get("bailOnFail"), "bail-on-fail should be stripped from rule config")
        assertEquals(
            "value",
            ruleConfig
                ?.jsonObject
                ?.get("someOption")
                ?.jsonPrimitive
                ?.content
        )
    }

    @Test
    fun `boolean true rule shorthand enables the rule with an empty config`() {
        val raw = mapOf("rules" to mapOf("commit-messages" to true, "labels" to false))
        val ruleConfigs = ConfigMapParser
            .parseFullConfig(raw)
            .config.rules.ruleConfigs
        assertEquals<JsonElement?>(JsonObject(emptyMap()), ruleConfigs["commitMessages"])
        assertFalse("labels" in ruleConfigs, "'labels: false' must not run the rule")
    }

    @Test
    fun `parseFullConfig extracts priority into ruleMeta`() {
        val raw = mapOf(
            "rules" to mapOf(
                "draft-prevention" to mapOf("priority" to 1, "bail-on-fail" to true, "block-draft" to true)
            )
        )
        val config = ConfigMapParser.parseFullConfig(raw).config
        val meta = config.rules.metaFor("draftPrevention")
        assertEquals(1, meta.priority)
        assertTrue(meta.bailOnFail)
    }

    @Test
    fun `parseFullConfig uses default meta when no priority declared`() {
        val raw = mapOf(
            "rules" to mapOf(
                "title-format" to mapOf("pattern" to "^feat:")
            )
        )
        val config = ConfigMapParser.parseFullConfig(raw).config
        val meta = config.rules.metaFor("titleFormat")
        assertEquals(RuleExecutionMeta.DEFAULT_PRIORITY, meta.priority)
        assertFalse(meta.bailOnFail)
    }

    @Test
    fun `parseFullConfig extracts when into ruleMeta`() {
        val raw = mapOf(
            "rules" to mapOf(
                "ai-analysis" to mapOf(
                    "when" to "not(is_draft) && file_path_matches('src/**')",
                    "provider" to "openai"
                )
            )
        )
        val config = ConfigMapParser.parseFullConfig(raw).config
        val meta = config.rules.metaFor("aiAnalysis")
        assertEquals("not(is_draft) && file_path_matches('src/**')", meta.`when`?.expression)
    }

    @Test
    fun `parseFullConfig extracts mode into ruleMeta`() {
        val raw = mapOf(
            "rules" to mapOf(
                "ai-analysis" to mapOf("mode" to "supplement", "priority" to 200)
            )
        )
        val config = ConfigMapParser.parseFullConfig(raw).config
        val meta = config.rules.metaFor("aiAnalysis")
        assertEquals(RuleMode.SUPPLEMENT, meta.mode)
        assertEquals(200, meta.priority)
    }

    @Test
    fun `parseFullConfig parses fallback mode`() {
        val raw = mapOf(
            "rules" to mapOf(
                "ai-fix" to mapOf("mode" to "fallback")
            )
        )
        val config = ConfigMapParser.parseFullConfig(raw).config
        val meta = config.rules.metaFor("aiFix")
        assertEquals(RuleMode.FALLBACK, meta.mode)
    }

    @Test
    fun `parseFullConfig defaults mode to NORMAL when not specified`() {
        val raw = mapOf(
            "rules" to mapOf(
                "title-format" to mapOf("pattern" to "^feat:")
            )
        )
        val config = ConfigMapParser.parseFullConfig(raw).config
        val meta = config.rules.metaFor("titleFormat")
        assertEquals(RuleMode.NORMAL, meta.mode)
        assertNull(meta.`when`)
    }

    @Test
    fun `parseFullConfig parses allow-localhost true`() {
        val raw = mapOf("allow-localhost" to true)
        val config = ConfigMapParser.parseFullConfig(raw).config
        assertTrue(config.allowLocalhost)
    }

    @Test
    fun `parseFullConfig parses allow-localhost false`() {
        val raw = mapOf("allow-localhost" to false)
        val config = ConfigMapParser.parseFullConfig(raw).config
        assertFalse(config.allowLocalhost)
    }

    @Test
    fun `parseFullConfig parses allowLocalhost camelCase variant`() {
        val raw = mapOf("allowLocalhost" to true)
        val config = ConfigMapParser.parseFullConfig(raw).config
        assertTrue(config.allowLocalhost)
    }

    @Test
    fun `parseFullConfig defaults allowLocalhost to false when key is absent`() {
        val raw = emptyMap<String, Any?>()
        val config = ConfigMapParser.parseFullConfig(raw).config
        assertFalse(config.allowLocalhost)
    }

    @Test
    fun `parseFullConfig parses allow_localhost snake_case variant`() {
        val raw = mapOf("allow_localhost" to true)
        val config = ConfigMapParser.parseFullConfig(raw).config
        assertTrue(config.allowLocalhost)
    }

    @Test
    fun `parseFullConfig parses allow_private_network in snake and kebab case`() {
        for (key in listOf("allow_private_network", "allow-private-network", "allowPrivateNetwork")) {
            assertTrue(ConfigMapParser.parseFullConfig(mapOf(key to true)).config.allowPrivateNetwork, key)
        }
        assertFalse(ConfigMapParser.parseFullConfig(emptyMap<String, Any?>()).config.allowPrivateNetwork)
    }

    @Test
    fun `parseFullConfig parses dry_run snake_case variant`() {
        val raw = mapOf("dry_run" to true)
        val config = ConfigMapParser.parseFullConfig(raw).config
        assertTrue(config.dryRun)
    }

    @Test
    fun `parseFullConfig parses dry_run_scope snake_case variant`() {
        val raw = mapOf("dry_run_scope" to "ai")
        val config = ConfigMapParser.parseFullConfig(raw).config
        assertEquals(DryRunScope.AI, config.dryRunScope)
    }

    @Test
    fun `parseFullConfig parses bail_on_fail snake_case variant in rule meta`() {
        val raw = mapOf(
            "rules" to mapOf(
                "my-rule" to mapOf("bail_on_fail" to true, "some-option" to "value")
            )
        )
        val config = ConfigMapParser.parseFullConfig(raw).config
        assertTrue(config.rules.metaFor("myRule").bailOnFail)
    }

    @Test
    fun `parseFullConfig parses when key in rule meta`() {
        val raw = mapOf(
            "rules" to mapOf(
                "my-rule" to mapOf("when" to "is_draft")
            )
        )
        val config = ConfigMapParser.parseFullConfig(raw).config
        assertEquals(
            "is_draft",
            config.rules
                .metaFor("myRule")
                .`when`
                ?.expression
        )
    }

    @Test
    fun `parseFullConfig strips snake_case meta keys from rule config`() {
        val raw = mapOf(
            "rules" to mapOf(
                "my-rule" to mapOf(
                    "bail_on_fail" to true,
                    "when" to "is_draft",
                    "actual-config" to "value"
                )
            )
        )
        val config = ConfigMapParser.parseFullConfig(raw).config
        val ruleConfig = config.rules.ruleConfigs["myRule"]!!.jsonObject
        assertNull(ruleConfig["bail_on_fail"])
        assertNull(ruleConfig["bailOnFail"])
        assertNull(ruleConfig["when"])
        assertEquals("value", ruleConfig["actualConfig"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parseFullConfig strips meta keys from rule config`() {
        val raw = mapOf(
            "rules" to mapOf(
                "my-rule" to mapOf(
                    "priority" to 50,
                    "bail-on-fail" to true,
                    "when" to "is_draft",
                    "mode" to "supplement",
                    "actual-config" to "value"
                )
            )
        )
        val config = ConfigMapParser.parseFullConfig(raw).config
        val ruleConfig = config.rules.ruleConfigs["myRule"]!!.jsonObject
        assertNull(ruleConfig["priority"])
        assertNull(ruleConfig["bailOnFail"])
        assertNull(ruleConfig["when"])
        assertNull(ruleConfig["mode"])
        assertEquals("value", ruleConfig["actualConfig"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parseFullConfig extracts suggestion into ruleMeta`() {
        val raw = mapOf(
            "rules" to mapOf(
                "title-format" to mapOf(
                    "pattern" to "^feat:",
                    "suggestion" to "Use feat: prefix"
                )
            )
        )
        val config = ConfigMapParser.parseFullConfig(raw).config
        val meta = config.rules.metaFor("titleFormat")
        assertEquals("Use feat: prefix", meta.suggestion)
    }

    @Test
    fun `parseFullConfig extracts explanation into ruleMeta`() {
        val raw = mapOf(
            "rules" to mapOf(
                "title-format" to mapOf(
                    "pattern" to "^feat:",
                    "explanation" to "We use conventional commits"
                )
            )
        )
        val config = ConfigMapParser.parseFullConfig(raw).config
        val meta = config.rules.metaFor("titleFormat")
        assertEquals("We use conventional commits", meta.explanation)
    }

    @Test
    fun `parseFullConfig strips suggestion and explanation from rule config`() {
        val raw = mapOf(
            "rules" to mapOf(
                "my-rule" to mapOf(
                    "suggestion" to "Fix it this way",
                    "explanation" to "Because reasons",
                    "actual-config" to "value"
                )
            )
        )
        val config = ConfigMapParser.parseFullConfig(raw).config
        val ruleConfig = config.rules.ruleConfigs["myRule"]!!.jsonObject
        assertNull(ruleConfig["suggestion"], "suggestion should be stripped from rule config")
        assertNull(ruleConfig["explanation"], "explanation should be stripped from rule config")
        assertEquals("value", ruleConfig["actualConfig"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parseFullConfig suggestion and explanation default to null`() {
        val raw = mapOf(
            "rules" to mapOf(
                "title-format" to mapOf("pattern" to "^feat:")
            )
        )
        val config = ConfigMapParser.parseFullConfig(raw).config
        val meta = config.rules.metaFor("titleFormat")
        assertNull(meta.suggestion)
        assertNull(meta.explanation)
    }

    @Test
    fun `parseFullConfig parses when string form into WhenConfig`() {
        val raw = mapOf(
            "rules" to mapOf(
                "jira-ticket" to mapOf("when" to "has_ticket(PROJ)")
            )
        )
        val config = ConfigMapParser.parseFullConfig(raw).config
        val whenConfig = config.rules.metaFor("jiraTicket").`when`
        assertNotNull(whenConfig)
        assertEquals("has_ticket(PROJ)", whenConfig.expression)
        assertNull(whenConfig.override)
    }

    @Test
    fun `parseFullConfig parses when object form with expression and override`() {
        val raw = mapOf(
            "rules" to mapOf(
                "commit-body" to mapOf(
                    "when" to mapOf(
                        "expression" to "has_ticket(PROJ)",
                        "override" to mapOf("required" to true)
                    )
                )
            )
        )
        val config = ConfigMapParser.parseFullConfig(raw).config
        val whenConfig = config.rules.metaFor("commitBody").`when`
        assertNotNull(whenConfig)
        assertEquals("has_ticket(PROJ)", whenConfig.expression)
        val override = assertNotNull(whenConfig.override)
        assertEquals("true", override["required"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parseFullConfig strips when from rule config`() {
        val raw = mapOf(
            "rules" to mapOf(
                "title-format" to mapOf(
                    "when" to "is_draft",
                    "pattern" to "^feat:"
                )
            )
        )
        val config = ConfigMapParser.parseFullConfig(raw).config
        val ruleConfig = config.rules.ruleConfigs["titleFormat"]!!.jsonObject
        assertNull(ruleConfig["when"], "when should be stripped from rule config")
        assertEquals("^feat:", ruleConfig["pattern"]?.jsonPrimitive?.content)
    }

    @Test
    fun `scalar severity becomes rule meta`() {
        val raw = mapOf(
            "rules" to mapOf(
                "conflict" to mapOf("severity" to "warning"),
            ),
        )
        val config = ConfigMapParser.parseFullConfig(raw).config

        assertEquals("warning", config.rules.metaFor("conflict").severity)
        assertNull(
            config.rules.ruleConfigs["conflict"]
                ?.jsonObject
                ?.get("severity")
        )
    }

    @Test
    fun `draft rule key configures draftPrevention unless draftPrevention is set`() {
        val aliased = ConfigMapParser.parseFullConfig(mapOf("rules" to mapOf("draft" to mapOf("block-draft" to false)))).config
        assertEquals(
            "false",
            aliased.rules.ruleConfigs["draftPrevention"]
                ?.jsonObject
                ?.get("blockDraft")
                ?.jsonPrimitive
                ?.content
        )
        assertNull(aliased.rules.ruleConfigs["draft"])

        val both = ConfigMapParser
            .parseFullConfig(
                mapOf("rules" to mapOf("draft" to mapOf("block-draft" to false), "draft-prevention" to mapOf("block-draft" to true))),
            ).config
        assertEquals(
            "true",
            both.rules.ruleConfigs["draftPrevention"]
                ?.jsonObject
                ?.get("blockDraft")
                ?.jsonPrimitive
                ?.content
        )
    }
}
