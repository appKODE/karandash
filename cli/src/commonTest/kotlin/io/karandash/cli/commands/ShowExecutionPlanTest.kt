package io.karandash.cli.commands

import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.pipeline.ValidationOptions
import io.karandash.cli.pipeline.ValidationPipeline
import io.karandash.config.Config
import io.karandash.config.RuleExecutionMeta
import io.karandash.config.RulesConfig
import io.karandash.sdk.Logger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShowExecutionPlanTest {
    private class CapturingLogger : Logger {
        val messages = mutableListOf<String>()

        override val isDebugEnabled: Boolean get() = true

        override fun debug(message: String) {
            messages.add(message)
        }

        override fun info(message: String) {
            messages.add(message)
        }

        override fun warn(message: String) {
            messages.add(message)
        }

        override fun error(message: String) {
            messages.add(message)
        }
    }

    private suspend fun makePipeline(logger: Logger): ValidationPipeline {
        val bootstrap = PluginBootstrap()
        bootstrap.loadPlugins()
        return ValidationPipeline(
            ValidationOptions(
                configPathStr = null,
                mrDataStr = null,
                mrId = null,
                platform = null,
                project = null,
                prUrl = null,
                format = "console",
                outputPathStr = null,
                quiet = false,
                verbose = false,
                strict = false,
                dryRun = false,
                showExecutionPlan = true,
                explain = false,
                fix = false,
                yes = false,
                noAutoDetect = false,
                outputWriter = {},
                sharedBootstrap = bootstrap,
                logger = logger,
            ),
            sharedBootstrap = bootstrap
        )
    }

    @Test
    fun `execution plan groups rules by priority and prints header`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "title-format" to JsonObject(emptyMap()),
                        "commit-messages" to JsonObject(emptyMap()),
                        "branch-naming" to JsonObject(emptyMap()),
                    ),
                    ruleMeta = mapOf(
                        "branch-naming" to RuleExecutionMeta(priority = 10, bailOnFail = true),
                    )
                )
            )

            val logger = CapturingLogger()
            makePipeline(logger).printExecutionPlan(config)
            val output = logger.messages

            // Summary line is first and exact
            assertEquals("Execution Plan: 3 rule(s), 2 priority group(s)", output[0])

            // Header line is second
            assertTrue(output[1].startsWith("Priority"), "Header line missing")

            // branch-naming: priority 10, bail=yes
            val branchRow = output.first { it.startsWith("10") && it.contains("branch-naming") }
            assertTrue(branchRow.endsWith("yes"), "bail-on-fail=yes missing for branch-naming")

            // title-format: default priority, bail=no
            val titleRow = output.first { it.contains("title-format") }
            assertTrue(
                titleRow.startsWith("${RuleExecutionMeta.DEFAULT_PRIORITY}"),
                "title-format should have default priority"
            )
            assertTrue(titleRow.endsWith("  no"), "bail-on-fail should default to no for title-format")
        }

    @Test
    fun `execution plan with no explicit meta uses default priority`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "title-format" to JsonObject(emptyMap()),
                        "commit-messages" to JsonObject(emptyMap()),
                    ),
                    ruleMeta = emptyMap()
                )
            )

            val logger = CapturingLogger()
            makePipeline(logger).printExecutionPlan(config)
            val output = logger.messages

            assertEquals("Execution Plan: 2 rule(s), 1 priority group(s)", output[0])

            // All data rows (after summary[0], header[1], separator[2]) use default priority and no bail
            val dataRows = output.drop(3)
            assertTrue(dataRows.isNotEmpty(), "Expected data rows")
            assertTrue(
                dataRows.all { it.startsWith("${RuleExecutionMeta.DEFAULT_PRIORITY}") },
                "All rows should have default priority"
            )
            assertTrue(
                dataRows.all { it.endsWith("  no") },
                "All rows should have bail-on-fail=no"
            )
        }

    @Test
    fun `execution plan with empty rules prints zero summary`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = emptyMap(),
                    ruleMeta = emptyMap()
                )
            )

            val logger = CapturingLogger()
            makePipeline(logger).printExecutionPlan(config)
            val output = logger.messages

            assertEquals("Execution Plan: 0 rule(s), 0 priority group(s)", output[0])
        }
}
