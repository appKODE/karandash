package io.karandash.plugins.ai.core

import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerModule

/**
 * Base of the first-party analyzers: the rule [analyzerId] and config [featureKey] they are built with, version
 * `1.0.0`, and an [initialize] that accepts any config (an analyzer reads its config per call from the context).
 */
abstract class AbstractAiAnalyzer(
    final override val analyzerId: String,
    final override val featureKey: String,
) : AiAnalyzerModule {
    override val version: String get() = "1.0.0"

    override suspend fun initialize(context: PluginContext): Boolean = true
}
