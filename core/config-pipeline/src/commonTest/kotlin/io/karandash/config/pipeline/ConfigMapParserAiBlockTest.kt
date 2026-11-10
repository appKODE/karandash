package io.karandash.config.pipeline

import io.karandash.config.RuleMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for top-level `ai:` config block parsing in [ConfigMapParser].
 *
 * Covers:
 * - Top-level `ai:` parsed correctly with shared fields and per-sub-rule config
 * - Per-sub-rule enabled/disabled, temperature/model/maxTokens overrides
 * - `custom-rules:` parsed and synthesized into ruleConfigs
 * - `rules: ai:` stays a regular rule
 * - Both present: top-level `ai:` wins
 * - Neither present: no AI config
 */
class ConfigMapParserAiBlockTest {
    /** Mirror of the built-in AI analyzer featureKey→analyzerId mapping for test use. */
    private val builtInAiKeys: Map<String, String> = mapOf(
        "riskScoring" to "risk-scoring",
        "descriptionQuality" to "description-quality",
        "changeCategorization" to "change-categorization",
        "reviewComments" to "review-comments",
        "similarIssues" to "similar-issues",
        "fixSuggestions" to "fix-suggestions",
        "codeSuggestions" to "code-suggestions",
        "prSplit" to "pr-split",
        "generateDescription" to "generate-description",
        "outdatedDocs" to "outdated-docs",
        "reviewThreadAnalysis" to "review-thread-analysis",
        "mrSummary" to "mr-summary",
    )

    // --- Top-level ai: block ---

    @Test
    fun `top-level ai block parsed with shared fields`() {
        val data = mapOf<String, Any?>(
            "ai" to mapOf(
                "provider" to "litellm",
                "model" to "kimi-2.5",
                "max-token-budget" to 30000,
                "fail-on-error" to false,
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data).config

        assertNotNull(config.ai)
        assertEquals(
            "litellm",
            config.ai!!
                .jsonObject["provider"]
                ?.jsonPrimitive
                ?.content
        )
        assertEquals(
            "kimi-2.5",
            config.ai!!
                .jsonObject["model"]
                ?.jsonPrimitive
                ?.content
        )
        assertEquals(
            30000,
            config.ai!!
                .jsonObject["maxTokenBudget"]
                ?.jsonPrimitive
                ?.int
        )
        assertEquals(
            false,
            config.ai!!
                .jsonObject["failOnError"]
                ?.jsonPrimitive
                ?.boolean
        )
    }

    @Test
    fun `top-level ai block with enabled sub-rules synthesizes ruleConfigs`() {
        val data = mapOf<String, Any?>(
            "ai" to mapOf(
                "provider" to "openai",
                "risk-scoring" to mapOf("enabled" to true),
                "review-comments" to mapOf("enabled" to true),
                "description-quality" to mapOf("enabled" to false),
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data, aiSubRuleKeys = builtInAiKeys).config

        assertNotNull(config.ai)
        // Enabled sub-rules produce ruleConfigs entries (camelCase of kebab sub-rule ID)
        assertTrue("riskScoring" in config.rules.ruleConfigs, "aiRiskScoring should be synthesized")
        assertTrue("reviewComments" in config.rules.ruleConfigs, "aiReviewComments should be synthesized")
        // Disabled sub-rule should NOT produce a ruleConfigs entry
        assertTrue("descriptionQuality" !in config.rules.ruleConfigs, "disabled rule should not be synthesized")
    }

    @Test
    fun `an ai sub-rule key in every notation synthesizes the camelCase rules id`() {
        val synthesized = listOf("risk-scoring", "risk_scoring", "riskScoring").map { key ->
            val data = mapOf<String, Any?>("ai" to mapOf(key to mapOf("enabled" to true)))
            ConfigMapParser
                .parseFullConfig(data, aiSubRuleKeys = builtInAiKeys)
                .config.rules.ruleConfigs.keys
        }
        assertEquals(List(3) { setOf("riskScoring") }, synthesized)
    }

    @Test
    fun `sub-rule enabled defaults to true when enabled key is absent`() {
        val data = mapOf<String, Any?>(
            "ai" to mapOf(
                "risk-scoring" to mapOf("temperature" to 0.5),
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data, aiSubRuleKeys = builtInAiKeys).config

        assertTrue("riskScoring" in config.rules.ruleConfigs, "sub-rule without explicit enabled should default to true")
    }

    @Test
    fun `sub-rule as boolean true enables it`() {
        val data = mapOf<String, Any?>(
            "ai" to mapOf(
                "pr-split" to true,
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data, aiSubRuleKeys = builtInAiKeys).config

        assertTrue("prSplit" in config.rules.ruleConfigs)
    }

    @Test
    fun `sub-rule as boolean false disables it`() {
        val data = mapOf<String, Any?>(
            "ai" to mapOf(
                "pr-split" to false,
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data, aiSubRuleKeys = builtInAiKeys).config

        assertTrue("prSplit" !in config.rules.ruleConfigs)
    }

    // --- Custom rules ---

    @Test
    fun `custom-rules parsed and added to ruleConfigs`() {
        val data = mapOf<String, Any?>(
            "ai" to mapOf(
                "custom-rules" to listOf(
                    mapOf("id" to "maps-platform-check", "name" to "Platform Check", "prompt" to "Check platform code"),
                    mapOf("id" to "maps-perf-check", "name" to "Perf Check", "prompt" to "Check performance"),
                ),
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data).config

        // keyed like the AI module registers them: `ai-` prefixed, camelCased
        assertTrue("aiMapsPlatformCheck" in config.rules.ruleConfigs)
        assertTrue("aiMapsPerfCheck" in config.rules.ruleConfigs)
        assertTrue("maps-platform-check" !in config.rules.ruleConfigs)
    }

    @Test
    fun `custom-rules id already prefixed with ai- is not prefixed twice`() {
        val data = mapOf<String, Any?>(
            "ai" to mapOf(
                "custom-rules" to listOf(mapOf("id" to "ai-changelog", "name" to "Changelog", "prompt" to "Check")),
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data).config

        assertTrue("aiChangelog" in config.rules.ruleConfigs)
        assertTrue("aiChangelog" in config.rules.ruleMeta)
    }

    @Test
    fun `custom-rules entry without id is skipped`() {
        val data = mapOf<String, Any?>(
            "ai" to mapOf(
                "custom-rules" to listOf(
                    mapOf("name" to "No ID Rule", "prompt" to "check something"),
                ),
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data).config

        // Only the synthesized entries from recognized sub-rules (none here)
        assertTrue(config.rules.ruleConfigs.isEmpty())
    }

    // --- Pre-merge checks ---

    @Test
    fun `checks synthesize the pre-merge-checks rule`() {
        val data = mapOf<String, Any?>(
            "ai" to mapOf("checks" to listOf(mapOf("name" to "No secrets", "instruction" to "No tokens are logged"))),
        )
        val config = ConfigMapParser.parseFullConfig(data).config

        assertTrue("preMergeChecks" in config.rules.ruleConfigs)
        assertEquals(200, config.rules.ruleMeta["preMergeChecks"]?.priority)
    }

    @Test
    fun `ticket compliance alone synthesizes the pre-merge-checks rule`() {
        val data = mapOf<String, Any?>("ai" to mapOf("checks-ticket-compliance" to true))
        val config = ConfigMapParser.parseFullConfig(data).config

        assertTrue("preMergeChecks" in config.rules.ruleConfigs)
    }

    @Test
    fun `empty checks with ticket compliance off synthesize no pre-merge-checks rule`() {
        val data = mapOf<String, Any?>("ai" to mapOf("checks" to emptyList<Any>(), "checks-ticket-compliance" to false))
        val config = ConfigMapParser.parseFullConfig(data).config

        assertTrue(config.rules.ruleConfigs.isEmpty())
    }

    // --- rules: ai: is NOT promoted ---

    @Test
    fun `rules ai block is treated as a regular rule not promoted to config ai`() {
        val data = mapOf<String, Any?>(
            "rules" to mapOf(
                "ai" to mapOf(
                    "provider" to "openai",
                ),
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data).config

        assertNull(config.ai, "rules: ai: should NOT be promoted to config.ai")
        assertTrue("ai" in config.rules.ruleConfigs, "ai should remain as a regular ruleConfigs entry")
    }

    // --- Both present: they are independent ---

    @Test
    fun `top-level ai and rules ai are independent`() {
        val data = mapOf<String, Any?>(
            "ai" to mapOf(
                "provider" to "litellm",
                "risk-scoring" to mapOf("enabled" to true),
            ),
            "rules" to mapOf(
                "ai" to mapOf(
                    "provider" to "openai",
                ),
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data, aiSubRuleKeys = builtInAiKeys).config

        assertNotNull(config.ai)
        assertEquals(
            "litellm",
            config.ai!!
                .jsonObject["provider"]
                ?.jsonPrimitive
                ?.content
        )
        assertTrue("riskScoring" in config.rules.ruleConfigs, "top-level sub-rules should be synthesized")
        assertTrue("ai" in config.rules.ruleConfigs, "rules: ai: should remain as regular rule")
    }

    // --- Neither present ---

    @Test
    fun `no ai config when neither top-level nor rules ai present`() {
        val data = mapOf<String, Any?>(
            "rules" to mapOf(
                "title-format" to mapOf("pattern" to "^feat:"),
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data).config

        assertNull(config.ai)
    }

    // --- Env var interpolation ---

    @Test
    fun `ai block interpolates env vars`() {
        val data = mapOf<String, Any?>(
            "ai" to mapOf(
                "provider" to "litellm",
                "base-url" to "\${LITELLM_URL}",
            ),
        )
        val defaults = mapOf("LITELLM_URL" to "http://localhost:4000")
        val config = ConfigMapParser.parseFullConfig(data, defaults).config

        assertNotNull(config.ai)
        assertEquals(
            "http://localhost:4000",
            config.ai!!
                .jsonObject["baseUrl"]
                ?.jsonPrimitive
                ?.content
        )
    }

    // --- Existing rules preserved alongside synthesized AI rules ---

    @Test
    fun `existing rule configs preserved alongside synthesized ai rules`() {
        val data = mapOf<String, Any?>(
            "rules" to mapOf(
                "title-format" to mapOf("pattern" to "^feat:"),
            ),
            "ai" to mapOf(
                "risk-scoring" to true,
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data, aiSubRuleKeys = builtInAiKeys).config

        assertTrue("titleFormat" in config.rules.ruleConfigs, "existing rules should be preserved")
        assertTrue("riskScoring" in config.rules.ruleConfigs, "synthesized AI rule should be added")
    }

    // --- Execution priority ---

    @Test
    fun `ai rules default to priority 200`() {
        val data = mapOf<String, Any?>(
            "ai" to mapOf(
                "risk-scoring" to true,
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data, aiSubRuleKeys = builtInAiKeys).config

        val meta = config.rules.metaFor("riskScoring")
        assertEquals(200, meta.priority, "AI rules should default to priority 200")
    }

    @Test
    fun `ai block priority overrides default for all sub-rules`() {
        val data = mapOf<String, Any?>(
            "ai" to mapOf(
                "priority" to 300,
                "risk-scoring" to true,
                "review-comments" to true,
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data, aiSubRuleKeys = builtInAiKeys).config

        assertEquals(300, config.rules.metaFor("riskScoring").priority)
        assertEquals(300, config.rules.metaFor("reviewComments").priority)
    }

    @Test
    fun `per-sub-rule priority overrides ai block default`() {
        val data = mapOf<String, Any?>(
            "ai" to mapOf(
                "priority" to 300,
                "risk-scoring" to mapOf("enabled" to true, "priority" to 150),
                "review-comments" to true,
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data, aiSubRuleKeys = builtInAiKeys).config

        assertEquals(150, config.rules.metaFor("riskScoring").priority, "per-rule priority wins")
        assertEquals(300, config.rules.metaFor("reviewComments").priority, "block default applies")
    }

    @Test
    fun `custom rules inherit ai block priority`() {
        val data = mapOf<String, Any?>(
            "ai" to mapOf(
                "priority" to 250,
                "custom-rules" to listOf(
                    mapOf("id" to "my-custom-rule", "prompt" to "check something"),
                ),
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data).config

        assertEquals(250, config.rules.metaFor("aiMyCustomRule").priority)
    }

    @Test
    fun `plain rules keep default priority 100 alongside ai rules at 200`() {
        val data = mapOf<String, Any?>(
            "rules" to mapOf(
                "title-format" to mapOf("pattern" to "^feat:"),
            ),
            "ai" to mapOf(
                "risk-scoring" to true,
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data, aiSubRuleKeys = builtInAiKeys).config

        assertEquals(100, config.rules.metaFor("titleFormat").priority, "plain rules keep default priority")
        assertEquals(200, config.rules.metaFor("riskScoring").priority, "AI rules get higher priority")
    }

    // --- Execution mode ---

    @Test
    fun `ai block mode applies to all sub-rules`() {
        val data = mapOf<String, Any?>(
            "ai" to mapOf(
                "mode" to "supplement",
                "risk-scoring" to true,
                "review-comments" to true,
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data, aiSubRuleKeys = builtInAiKeys).config

        assertEquals(RuleMode.SUPPLEMENT, config.rules.metaFor("riskScoring").mode)
        assertEquals(RuleMode.SUPPLEMENT, config.rules.metaFor("reviewComments").mode)
    }

    @Test
    fun `per-sub-rule mode overrides ai block default`() {
        val data = mapOf<String, Any?>(
            "ai" to mapOf(
                "mode" to "supplement",
                "risk-scoring" to mapOf("enabled" to true, "mode" to "fallback"),
                "review-comments" to true,
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data, aiSubRuleKeys = builtInAiKeys).config

        assertEquals(RuleMode.FALLBACK, config.rules.metaFor("riskScoring").mode)
        assertEquals(RuleMode.SUPPLEMENT, config.rules.metaFor("reviewComments").mode)
    }

    @Test
    fun `fix-suggestions runs in pass 2 after the deterministic rules by default`() {
        val data = mapOf<String, Any?>(
            "ai" to mapOf(
                "fix-suggestions" to true,
                "risk-scoring" to true,
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data, aiSubRuleKeys = builtInAiKeys).config

        assertEquals(RuleMode.SUPPLEMENT, config.rules.metaFor("fixSuggestions").mode)
        assertEquals(RuleMode.NORMAL, config.rules.metaFor("riskScoring").mode)
    }

    @Test
    fun `ai block concern-area applies to all sub-rules`() {
        val data = mapOf<String, Any?>(
            "ai" to mapOf(
                "mode" to "fallback",
                "concern-area" to "code-quality",
                "risk-scoring" to true,
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data, aiSubRuleKeys = builtInAiKeys).config

        assertEquals(RuleMode.FALLBACK, config.rules.metaFor("riskScoring").mode)
        assertEquals("code-quality", config.rules.metaFor("riskScoring").concernArea)
    }

    @Test
    fun `ai rules default to NORMAL mode when not specified`() {
        val data = mapOf<String, Any?>(
            "ai" to mapOf(
                "risk-scoring" to true,
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data, aiSubRuleKeys = builtInAiKeys).config

        assertEquals(RuleMode.NORMAL, config.rules.metaFor("riskScoring").mode)
    }

    // --- Scopes ---

    @Test
    fun `scopes block is passed through to ai config`() {
        val data = mapOf<String, Any?>(
            "ai" to mapOf(
                "provider" to "anthropic",
                "scopes" to mapOf(
                    "android" to mapOf(
                        "paths" to listOf("android/**", "app/src/**/*.kt"),
                        "instructions" to "Check Activity lifecycle issues.",
                        "best-practices" to "Use ViewModel.",
                    ),
                    "ios" to mapOf(
                        "paths" to listOf("ios/**", "**/*.swift"),
                        "instructions" to "Check for retain cycles.",
                    ),
                ),
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data).config

        assertNotNull(config.ai)
        val scopes = config.ai!!.jsonObject["scopes"]
        assertNotNull(scopes, "scopes should be present in AI config")
        assertTrue(scopes is JsonObject, "scopes should be a map")
    }

    @Test
    fun `scopes scope names are normalized to camelCase`() {
        val data = mapOf<String, Any?>(
            "ai" to mapOf(
                "provider" to "openai",
                "scopes" to mapOf(
                    "core-cpp" to mapOf(
                        "paths" to listOf("core/cpp/**"),
                        "best-practices" to "Use RAII.",
                    ),
                ),
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data).config

        assertNotNull(config.ai)
        val scopes = config.ai!!.jsonObject["scopes"] as? JsonObject
        assertNotNull(scopes)
        // scope name "core-cpp" is camelCased by deepNormalizeKeys
        assertTrue(scopes.containsKey("coreCpp"), "scope name should be camelCased")
        val inner = scopes["coreCpp"] as? JsonObject
        assertNotNull(inner)
        // inner schema keys are also normalized to camelCase by deepNormalizeKeys
        assertEquals("Use RAII.", inner["bestPractices"]?.jsonPrimitive?.content)
    }

    // --- knowledge-context budgets keep their ids ---

    @Test
    fun `knowledge-context budget keys keep their enrichment and module ids`() {
        val data = mapOf<String, Any?>(
            "ai" to mapOf(
                "context-quality" to mapOf(
                    "knowledge-context" to mapOf(
                        "budgets" to mapOf(
                            "full-file-context" to mapOf("max-chars" to 200),
                            "local-git:file-frequency" to mapOf("max-chars" to 300),
                        ),
                    ),
                ),
            ),
        )
        val ai = checkNotNull(ConfigMapParser.parseFullConfig(data).config.ai).jsonObject
        val budgets = ai
            .getValue("contextQuality")
            .jsonObject
            .getValue("knowledgeContext")
            .jsonObject
            .getValue("budgets")
            .jsonObject

        assertEquals(setOf("full-file-context", "local-git:file-frequency"), budgets.keys)
        assertEquals(
            200,
            budgets
                .getValue("full-file-context")
                .jsonObject
                .getValue("maxChars")
                .jsonPrimitive.int
        )
    }
}
