@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.cli.pipeline

import io.karandash.api.PluginContext
import io.karandash.api.module.ModuleUsage
import io.karandash.api.module.RuleModule
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.sdk.Logger
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals

/** the host reads [RuleModule.usage] at the end of the run and logs it. */
class RuleModuleUsageSummaryTest {
    private class UsageRule(
        override val id: String,
        override val usage: ModuleUsage?,
    ) : RuleModule {
        override val name = id
        override val version = "1.0"

        override suspend fun contributedRules(): Set<String> = setOf(id)

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ): List<Finding> = emptyList()
    }

    private class InfoLogger : Logger {
        val infos = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) {
            infos += message
        }

        override fun warn(message: String) = Unit

        override fun error(message: String) = Unit
    }

    @Test
    fun `rule module usage is logged once per module that used something`() {
        val logger = InfoLogger()
        val modules = listOf(
            UsageRule("llm-rule", ModuleUsage(tokens = 1200, requests = 3, costUsd = 0.5)),
            UsageRule("api-rule", ModuleUsage(requests = 2)),
            UsageRule("idle-rule", ModuleUsage()),
            UsageRule("plain-rule", null),
        )

        logRuleModuleUsage(modules, logger)

        assertEquals(
            listOf(
                "Module llm-rule usage: 1200 tokens, 3 requests, cost ~\$0.5",
                "Module api-rule usage: 0 tokens, 2 requests",
            ),
            logger.infos,
        )
    }
}
