package io.karandash.cli.plugin

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.ReporterModule
import io.karandash.api.module.RuleModule
import io.karandash.api.support.mapToJsonElement
import io.karandash.bootstrap.ModuleDefaultsCollector
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModuleDefaultsCollectorTest {
    private fun makeProvider(
        moduleId: String,
        priority: Int,
        authMsg: String?,
        apiUrl: String? = null,
        authName: String? = null,
    ) = object : PlatformServiceModule {
        override val id = moduleId
        override val name = moduleId
        override val version = "1.0"
        override val defaultPriority = priority
        override val dslName: String get() = authName ?: id

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun messages() =
            if (authMsg != null) {
                listOf(
                    io.karandash.api.finding
                        .ModuleMessage(io.karandash.api.finding.MessageCode.MISSING_AUTH, authMsg)
                )
            } else {
                emptyList()
            }

        override suspend fun defaultApiUrl(): String? = apiUrl

        override suspend fun fetchMergeRequest(
            projectId: String,
            mrId: String
        ): MergeRequest = error("stub")

        override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = error("stub")

        override suspend fun addLabels(
            projectId: String,
            mrId: String,
            labels: List<String>
        ) {}

        override suspend fun removeLabels(
            projectId: String,
            mrId: String,
            labels: List<String>
        ) {}

        override suspend fun setDraft(
            projectId: String,
            mrId: String,
            draft: Boolean
        ) {}

        override suspend fun updateTitle(
            projectId: String,
            mrId: String,
            title: String
        ) {}

        override suspend fun updateDescription(
            projectId: String,
            mrId: String,
            description: String
        ) {}

        override suspend fun approve(
            projectId: String,
            mrId: String
        ) {}

        override suspend fun unapprove(
            projectId: String,
            mrId: String
        ) {}

        override suspend fun postComment(
            projectId: String,
            mrId: String,
            body: String
        ) = ""

        override suspend fun updateComment(
            projectId: String,
            mrId: String,
            commentId: String,
            body: String
        ) {}

        override suspend fun findExistingComment(
            projectId: String,
            mrId: String,
            marker: String
        ): String? = null

        override suspend fun postInlineComment(
            projectId: String,
            mrId: String,
            body: String,
            filePath: String,
            line: Int,
            diffRefs: io.karandash.models.DiffRefs,
            startLine: Int?,
        ) = ""

        override suspend fun shutdown() {}
    }

    private fun makeReporter(
        moduleId: String,
        format: String
    ) = object : ReporterModule {
        override val id = moduleId
        override val name = moduleId
        override val version = "1.0"
        override val type get() = ModuleType.REPORTER
        override val formatId = format

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun report(
            findings: List<Finding>,
            passed: Boolean,
            config: JsonElement
        ): String? = null
    }

    private fun makeRule(
        moduleId: String,
        ruleIds: Set<String>,
        enabled: Boolean,
        priority: Int,
        config: Map<String, Any?> = emptyMap(),
    ) = object : RuleModule {
        override val id = moduleId
        override val name = moduleId
        override val version = "1.0"
        override val defaultEnabled = enabled
        override val defaultPriority = priority

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun contributedRules() = ruleIds

        override suspend fun defaultConfig(): JsonElement = mapToJsonElement(config)

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ) = emptyList<Finding>()
    }

    @Test
    fun `sortedProviders returns providers sorted by priority`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(makeProvider("github", 20, null))
            registry.register(makeProvider("gitlab", 10, null))
            registry.register(makeProvider("bitbucket", 30, null))

            val sorted = ModuleDefaultsCollector.sortedProviders(registry)
            assertEquals(listOf("gitlab", "github", "bitbucket"), sorted.map { it.id })
        }

    @Test
    fun `providerOrder returns IDs in priority order`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(makeProvider("github", 20, null))
            registry.register(makeProvider("gitlab", 10, null))

            assertEquals(listOf("gitlab", "github"), ModuleDefaultsCollector.providerOrder(registry))
        }

    @Test
    fun `missingAuthMessage returns module-declared message when present`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(makeProvider("gitlab", 10, "GitLab token missing"))

            val msg = ModuleDefaultsCollector.missingAuthMessage("gitlab", registry)
            assertEquals("GitLab token missing", msg)
        }

    @Test
    fun `missingAuthMessage falls back when module returns null`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(makeProvider("unknown-provider", 99, null))

            val msg = ModuleDefaultsCollector.missingAuthMessage("unknown-provider", registry)
            assertNull(msg)
        }

    @Test
    fun `defaultEnabledRules returns only rules with isDefaultEnabled true`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(makeRule("ruleA", setOf("ruleA"), enabled = true, priority = 10))
            registry.register(makeRule("ruleB", setOf("ruleB"), enabled = false, priority = 5))
            registry.register(makeRule("ruleC", setOf("ruleC"), enabled = true, priority = 20))

            val enabled = ModuleDefaultsCollector.defaultEnabledRules(registry)
            assertEquals(2, enabled.size)
            assertEquals(listOf("ruleA", "ruleC"), enabled.map { it.id })
        }

    @Test
    fun `zeroConfigRuleConfigs collects configs from default-enabled rules`() =
        runTest {
            val config = mapOf("key" to "value")
            val registry = ModuleRegistryImpl()
            registry.register(makeRule("ruleA", setOf("ruleId1"), enabled = true, priority = 10, config = config))
            registry.register(makeRule("ruleB", setOf("ruleId2"), enabled = false, priority = 5, config = mapOf("k" to "v")))

            val result = ModuleDefaultsCollector.zeroConfigRuleConfigs(registry)
            assertEquals(1, result.size)
            val ruleConfig = result["ruleId1"] as? JsonObject
            assertEquals(mapToJsonElement(config), ruleConfig)
        }

    @Test
    fun `allRuleIds returns union of all contributed rule IDs`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(makeRule("modA", setOf("rule1", "rule2"), enabled = true, priority = 10))
            registry.register(makeRule("modB", setOf("rule3"), enabled = false, priority = 5))

            val ids = ModuleDefaultsCollector.allRuleIds(registry)
            assertEquals(setOf("rule1", "rule2", "rule3"), ids)
        }

    @Test
    fun `sortedProviders returns empty list when no providers registered`() =
        runTest {
            val registry = ModuleRegistryImpl()
            assertTrue(ModuleDefaultsCollector.sortedProviders(registry).isEmpty())
        }

    @Test
    fun `defaultEnabledRules returns empty list when no rules registered`() =
        runTest {
            val registry = ModuleRegistryImpl()
            assertTrue(ModuleDefaultsCollector.defaultEnabledRules(registry).isEmpty())
        }

    @Test
    fun `zeroConfigRuleConfigs excludes rules with empty config`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(makeRule("ruleA", setOf("ruleId1"), enabled = true, priority = 10, config = emptyMap()))

            val result = ModuleDefaultsCollector.zeroConfigRuleConfigs(registry)
            assertTrue(result.isEmpty())
        }

    @Test
    fun `allRuleIds returns empty set when no rules registered`() =
        runTest {
            val registry = ModuleRegistryImpl()
            assertTrue(ModuleDefaultsCollector.allRuleIds(registry).isEmpty())
        }

    @Test
    fun `allReporterFormats returns format IDs from registered reporters`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(makeReporter("r1", "json"))
            registry.register(makeReporter("r2", "slack"))
            registry.register(makeReporter("r3", "sarif"))

            assertEquals(setOf("json", "slack", "sarif"), ModuleDefaultsCollector.allReporterFormats(registry))
        }

    @Test
    fun `allReporterFormats returns empty set when no reporters registered`() =
        runTest {
            val registry = ModuleRegistryImpl()
            assertTrue(ModuleDefaultsCollector.allReporterFormats(registry).isEmpty())
        }

    @Test
    fun `allProviderApiUrls returns map of id to url for providers with non-null defaultApiUrl`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(makeProvider("gitlab", 10, null, apiUrl = "https://gitlab.com"))
            registry.register(makeProvider("github", 20, null, apiUrl = "https://api.github.com"))
            registry.register(makeProvider("gerrit", 30, null, apiUrl = null))

            val urls = ModuleDefaultsCollector.allProviderApiUrls(registry)
            assertEquals(mapOf("gitlab" to "https://gitlab.com", "github" to "https://api.github.com"), urls)
        }

    @Test
    fun `allProviderApiUrls returns empty map when no providers have defaultApiUrl`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(makeProvider("gerrit", 10, null, apiUrl = null))

            assertTrue(ModuleDefaultsCollector.allProviderApiUrls(registry).isEmpty())
        }

    @Test
    fun `allAuthDslNames returns dslName for all registered providers`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(makeProvider("gitlab", 10, null, authName = "gitlab"))
            registry.register(makeProvider("azure", 20, null, authName = "azureDevOps"))
            registry.register(makeProvider("aws", 30, null, authName = "awsCodeCommit"))

            assertEquals(setOf("gitlab", "azureDevOps", "awsCodeCommit"), ModuleDefaultsCollector.allAuthDslNames(registry))
        }

    @Test
    fun `allAuthDslNames returns empty set when no providers registered`() =
        runTest {
            val registry = ModuleRegistryImpl()
            assertTrue(ModuleDefaultsCollector.allAuthDslNames(registry).isEmpty())
        }
}
