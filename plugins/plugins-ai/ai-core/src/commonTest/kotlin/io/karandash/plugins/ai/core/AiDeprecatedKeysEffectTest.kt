package io.karandash.plugins.ai.core

import io.karandash.models.support.KarandashJson
import kotlin.test.Test
import kotlin.test.assertNotEquals

/**
 * A formerly dead `ai:` key changes the parsed AI config instead of parsing and doing nothing.
 * `ai.costBudget.maxCostPerRunUsd` is pinned here; the effect of `ai.consensus`, `ai.analyzer.tokens` and
 * `ai.enrichment.model`/`maxTokens` is pinned by `AiDeadKeysTest` and `AiDeadKeysWiringTest`, where each key is wired.
 */
class AiDeprecatedKeysEffectTest {
    private fun plugin(text: String) =
        AiPluginConfig.fromPluginConfig(
            AiPluginConfig.topLevelAiBlockToPluginConfigElement(KarandashJson.Default.parseToJsonElement(text)),
        )

    @Test
    fun `ai costBudget maxCostPerRunUsd changes the parsed plugin config`() {
        assertNotEquals(plugin("{}"), plugin("""{"costBudget":{"maxCostPerRunUsd":1.5}}"""))
    }
}
