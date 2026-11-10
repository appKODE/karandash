package io.karandash.plugins.ai.core.router

import io.karandash.plugins.ai.core.config.RoutingConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelRouterTest {
    private val twoTierConfig = ModelRouterConfig(
        tiers = listOf(
            ModelTier(name = "premium", models = listOf("gpt-4", "claude-3")),
            ModelTier(name = "standard", models = listOf("gpt-3.5", "claude-instant")),
        ),
        rules = listOf(
            RoutingRule(match = "review-comments", tier = "premium"),
            RoutingRule(match = "code-suggestions", tier = "premium"),
            RoutingRule(match = null, tier = "standard"),
        ),
    )

    // risk routing via ModelRouter.route, formerly RiskModelRouterTest
    private val riskTiers =
        listOf(ModelTier("strong", listOf("big")), ModelTier("mid", listOf("medium")), ModelTier("cheap", listOf("small")))
    private val riskConfig = RoutingConfig(tiers = riskTiers, rules = listOf(RoutingRule(match = "security", tier = "mid")))

    @Test
    fun selectModelsFromMatchingTier() {
        val router = ModelRouter(twoTierConfig)
        val models = router.selectModels("review-comments")
        assertEquals("gpt-4", models.first())
        assertEquals("claude-3", models[1])
    }

    @Test
    fun fallbackAddsNextTierModels() {
        val router = ModelRouter(twoTierConfig)
        val models = router.selectModels("review-comments")
        // Premium (2) + Standard (2) = 4 models total
        assertEquals(4, models.size)
        assertEquals("gpt-3.5", models[2])
        assertEquals("claude-instant", models[3])
    }

    @Test
    fun wildcardRuleMatchesUnknownAnalyzer() {
        val router = ModelRouter(twoTierConfig)
        val models = router.selectModels("unknown-analyzer")
        // null match catches all -> "standard" tier
        assertEquals("gpt-3.5", models.first())
    }

    @Test
    fun noFallbackWhenDisabled() {
        val config = twoTierConfig.copy(
            fallback = FallbackConfig(enabled = false),
        )
        val router = ModelRouter(config)
        val models = router.selectModels("review-comments")
        // Only premium tier models, no fallback
        assertEquals(2, models.size)
        assertEquals("gpt-4", models.first())
        assertEquals("claude-3", models[1])
    }

    @Test
    fun emptyConfigReturnsEmpty() {
        val router = ModelRouter(ModelRouterConfig())
        val models = router.selectModels("any-analyzer")
        assertTrue(models.isEmpty())
    }

    @Test
    fun selectPrimaryModelReturnsFirst() {
        val router = ModelRouter(twoTierConfig)
        val primary = router.selectPrimaryModel("review-comments")
        assertEquals("gpt-4", primary)
    }

    @Test
    fun selectPrimaryModelReturnsNullWhenEmpty() {
        val router = ModelRouter(ModelRouterConfig())
        val primary = router.selectPrimaryModel("any")
        assertNull(primary)
    }

    @Test
    fun noRulesDefaultsToFirstTier() {
        val config = ModelRouterConfig(
            tiers = listOf(
                ModelTier(name = "default", models = listOf("model-a")),
                ModelTier(name = "fallback", models = listOf("model-b")),
            ),
        )
        val router = ModelRouter(config)
        val models = router.selectModels("anything")
        assertEquals("model-a", models.first())
    }

    @Test
    fun unknownTierInRuleReturnsEmpty() {
        val config = ModelRouterConfig(
            tiers = listOf(ModelTier(name = "existing", models = listOf("m1"))),
            rules = listOf(RoutingRule(match = "test", tier = "nonexistent")),
        )
        val router = ModelRouter(config)
        val models = router.selectModels("test")
        assertTrue(models.isEmpty())
    }

    @Test
    fun configRoundTrips() {
        val config = ModelRouterConfig(
            strategy = RoutingStrategy.COST_OPTIMIZED,
            tiers = listOf(ModelTier(name = "t1", models = listOf("m1"))),
            fallback = FallbackConfig(maxRetries = 3),
        )
        val json = io.karandash.models.support.KarandashJson.Default.encodeToString(
            ModelRouterConfig.serializer(),
            config,
        )
        val decoded = io.karandash.models.support.KarandashJson.Default.decodeFromString(
            ModelRouterConfig.serializer(),
            json,
        )
        assertEquals(config.strategy, decoded.strategy)
        assertEquals(config.fallback.maxRetries, decoded.fallback.maxRetries)
    }

    @Test
    fun `low risk routes to the cheapest tier`() {
        assertEquals("small", ModelRouter.route(riskConfig, "review-comments", riskScore = 0.2))
    }

    @Test
    fun `high risk escalates to the strongest tier`() {
        assertEquals("big", ModelRouter.route(riskConfig, "review-comments", riskScore = 0.5))
    }

    @Test
    fun `high risk uses the tier a routing rule names`() {
        assertEquals("medium", ModelRouter.route(riskConfig, "ai-security", riskScore = 0.9))
    }

    @Test
    fun `a routing rule finds its tier by name wherever the tier sits and an unknown name picks no model`() {
        val reordered = riskConfig.copy(tiers = listOf(riskTiers[1], riskTiers[0], riskTiers[2]))
        val renamed = riskConfig.copy(tiers = riskTiers.map { it.copy(name = "x-${it.name}") })

        assertEquals("medium", ModelRouter.route(reordered, "ai-security", riskScore = 0.9))
        assertEquals("big", ModelRouter.route(riskConfig.copy(rules = listOf(RoutingRule("security", "strong"))), "ai-security", 0.9))
        assertNull(ModelRouter.route(renamed, "ai-security", riskScore = 0.9))
    }

    @Test
    fun `quality-first ignores the risk`() {
        assertEquals("big", ModelRouter.route(riskConfig.copy(strategy = RoutingStrategy.QUALITY_FIRST), "review-comments", 0.0))
    }

    @Test
    fun `unknown risk takes the rule tier`() {
        assertEquals("big", ModelRouter.route(riskConfig, "review-comments", riskScore = null))
    }

    @Test
    fun `disabled or tierless routing returns null`() {
        assertNull(ModelRouter.route(riskConfig.copy(enabled = false), "x", 0.0))
        assertNull(ModelRouter.route(RoutingConfig(), "x", 0.0))
        assertNull(ModelRouter.route(null, "x", 0.0))
    }
}
