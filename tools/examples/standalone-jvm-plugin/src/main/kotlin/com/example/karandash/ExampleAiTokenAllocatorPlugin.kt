package com.example.karandash

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiTokenAllocatorModule

/**
 * Standalone example Karandash AI token allocator plugin.
 *
 * Demonstrates implementing [AiTokenAllocatorModule] to distribute the total token
 * budget across AI rules. This example implements a priority-weighted allocation -
 * rules with lower priority values receive proportionally more tokens.
 *
 * When no allocator is registered, Karandash splits the budget equally across all
 * enabled AI rules. Register this plugin to apply a custom allocation strategy.
 *
 * Build: ./gradlew build
 * Install: cp build/libs/standalone-jvm-plugin.jar ~/.karandash/plugins/
 */
class ExampleAiTokenAllocatorPlugin : AiTokenAllocatorModule {
    override val id: String = "example-jvm-ai-token-allocator"
    override val name: String = "Example JVM AI Token Allocator Plugin"
    override val version: String = "1.0.0"
    override val type: ModuleType get() = ModuleType.AI_TOKEN_ALLOCATOR

    override suspend fun initialize(context: PluginContext): Boolean = true

    /**
     * Allocates tokens using an inverse-priority weighting strategy.
     *
     * Rules with lower [priorities] values receive proportionally more tokens.
     * Rules without an explicit priority are assigned a default weight of 100.
     * If [priorities] is empty, the budget is split equally across all rules.
     */
    override suspend fun allocate(
        rules: Set<String>,
        totalBudget: Int,
        priorities: Map<String, Int>,
    ): Map<String, Int> {
        if (rules.isEmpty()) return emptyMap()

        val defaultPriority = 100
        val weights = rules.associateWith { ruleId ->
            val priority = priorities[ruleId] ?: defaultPriority
            // Invert priority so lower numbers get higher weight
            (defaultPriority * 2 - priority).coerceAtLeast(1)
        }

        val totalWeight = weights.values.sum()
        return weights.mapValues { (_, weight) ->
            ((weight.toLong() * totalBudget) / totalWeight).toInt().coerceAtLeast(1)
        }
    }
}
