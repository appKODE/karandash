package io.karandash.sdk.module

import io.karandash.sdk.PluginContext

/**
 * Abstract base class for [RuleModule] implementations that do not use typed config.
 *
 * Provides a default [initialize] implementation that returns `true`, allowing
 * subclasses to skip initialization when no setup is required.
 *
 * For modules with typed configuration, use [AbstractTypedSingleRuleModule] instead.
 *
 * Usage:
 * ```kotlin
 * class MyRuleModule : AbstractRuleModule() {
 *     override val id = "my-rule"
 *     override val name = "My Rule"
 *     override val version = "1.7.0"
 *     override suspend fun contributedRules() = setOf("myRule")
 *     override suspend fun validate(ruleId: String, mr: MergeRequest, configElement: JsonElement) = emptyList()
 * }
 * ```
 */
public abstract class AbstractRuleModule : RuleModule {
    override val defaultEnabled: Boolean = false

    override suspend fun initialize(context: PluginContext): Boolean = true
}
