package io.karandash.plugins.ai.core

import io.karandash.percent.KarandashPercent
import io.karandash.plugins.ai.core.config.BetweennessConfig
import io.karandash.plugins.ai.core.config.EvtAutoThresholdConfig
import io.karandash.plugins.ai.core.config.RoutingConfig
import io.karandash.plugins.ai.core.research.BetweennessCentrality
import io.karandash.plugins.ai.core.research.EvtAutoThreshold
import io.karandash.plugins.ai.core.router.ModelRouter
import io.karandash.plugins.ai.core.router.ModelTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Effect of the `ai.research` betweenness and EVT keys and of `ai.routing.riskThreshold`. */
class AiResearchRoutingKeyBehaviourTest {
    @Test
    fun `research betweenness bottleneckThreshold decides which nodes are bottlenecks`() {
        val chain = mapOf("A" to setOf("B"), "B" to setOf("C"), "C" to setOf("D"), "D" to emptySet())

        val lenient = BetweennessCentrality.calculate(chain, BetweennessConfig())
        val strict = BetweennessCentrality.calculate(chain, BetweennessConfig(bottleneckThreshold = KarandashPercent.ofPercent(100)))

        assertTrue(lenient.any { it.isBottleneck })
        assertTrue(strict.none { it.isBottleneck })
    }

    @Test
    fun `research evtAutoThreshold tailFraction moves the estimated return level`() {
        val values = (1..100).map { it.toDouble() }
        val narrow = EvtAutoThresholdConfig(minSamples = 1, tailFraction = KarandashPercent.ofPercent(10))
        val wide = EvtAutoThresholdConfig(minSamples = 1, tailFraction = KarandashPercent.ofPercent(50))

        val narrowLevel = assertNotNull(EvtAutoThreshold.computeThreshold(values, 0.5, 0.0, narrow).returnLevel)
        val wideLevel = assertNotNull(EvtAutoThreshold.computeThreshold(values, 0.5, 0.0, wide).returnLevel)

        assertTrue(narrowLevel > wideLevel, "narrow=$narrowLevel wide=$wideLevel")
    }

    @Test
    fun `routing riskThreshold sets the risk at which a rule escalates to the strongest tier`() {
        val tiers = listOf(ModelTier("strong", listOf("big")), ModelTier("cheap", listOf("small")))
        val defaults = RoutingConfig(tiers = tiers)
        val raised = defaults.copy(riskThreshold = 0.8)

        assertEquals("big", ModelRouter.route(defaults, "review-comments", riskScore = 0.6))
        assertEquals("small", ModelRouter.route(raised, "review-comments", riskScore = 0.6))
    }

    @Test
    fun `review instructions name and text are what the prompt block shows`() {
        val block = ScopeMatcher.buildInstructionBlock(listOf(ReviewInstruction("kotlin-style", listOf("src/**"), "prefer val")))

        assertEquals("## Review instruction: kotlin-style\nprefer val", block)
    }
}
