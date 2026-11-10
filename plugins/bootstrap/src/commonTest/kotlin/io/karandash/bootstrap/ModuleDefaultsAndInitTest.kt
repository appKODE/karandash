package io.karandash.bootstrap

import io.karandash.api.InternalKarandashApi
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.errors.ProviderException
import io.karandash.api.finding.MessageCode
import io.karandash.api.finding.ModuleMessage
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.ReporterModule
import io.karandash.api.module.RuleModule
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@OptIn(InternalKarandashApi::class)
class ModuleDefaultsAndInitTest {
    private class Rule(
        override val id: String,
        private val rules: Set<String>,
        override val defaultEnabled: Boolean = true,
        override val defaultPriority: Int = 100,
        private val config: JsonElement = JsonObject(emptyMap()),
        private val init: (PluginContext) -> Boolean = { true },
    ) : RuleModule {
        override val name = id
        override val version = "1"
        val seenPrefixes = mutableListOf<Map<String, List<String>>>()
        val seenConfigs = mutableListOf<JsonElement>()

        override suspend fun contributedRules() = rules

        override suspend fun initialize(context: PluginContext) = init(context)

        override suspend fun initialize(
            context: PluginContext,
            ruleEnrichmentBindingPrefixes: Map<String, List<String>>,
        ): Boolean {
            seenPrefixes += ruleEnrichmentBindingPrefixes
            seenConfigs += context.pluginConfig
            return initialize(context)
        }

        override suspend fun defaultConfig() = config

        override suspend fun ruleExplanation(ruleId: String) = if (ruleId.endsWith("x")) null else "explains $ruleId"

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ): List<Finding> = emptyList()
    }

    private class Platform(
        override val id: String,
        override val defaultPriority: Int,
        private val apiUrl: String?,
        private val authHint: Boolean,
    ) : PlatformServiceModule {
        override val name = id
        override val version = "1"
        override val dslName = "$id-dsl"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun defaultApiUrl() = apiUrl

        override suspend fun messages() =
            if (authHint) listOf(ModuleMessage(MessageCode.MISSING_AUTH, "$id needs a token")) else emptyList()

        override suspend fun fetchMergeRequest(
            projectId: String,
            mrId: String,
        ): MergeRequest = error("unused")

        override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = error("unused")

        override suspend fun postComment(
            projectId: String,
            mrId: String,
            body: String,
        ) = ""

        override suspend fun updateComment(
            projectId: String,
            mrId: String,
            commentId: String,
            body: String,
        ) = Unit

        override suspend fun findExistingComment(
            projectId: String,
            mrId: String,
            marker: String,
        ): String? = null

        override suspend fun postInlineComment(
            projectId: String,
            mrId: String,
            body: String,
            filePath: String,
            line: Int,
            diffRefs: DiffRefs,
            startLine: Int?,
        ) = ""

        override suspend fun addLabels(
            projectId: String,
            mrId: String,
            labels: List<String>,
        ) = Unit

        override suspend fun removeLabels(
            projectId: String,
            mrId: String,
            labels: List<String>,
        ) = Unit

        override suspend fun setDraft(
            projectId: String,
            mrId: String,
            draft: Boolean,
        ) = Unit

        override suspend fun updateTitle(
            projectId: String,
            mrId: String,
            title: String,
        ) = Unit

        override suspend fun updateDescription(
            projectId: String,
            mrId: String,
            description: String,
        ) = Unit

        override suspend fun approve(
            projectId: String,
            mrId: String,
        ) = Unit

        override suspend fun unapprove(
            projectId: String,
            mrId: String,
        ) = Unit
    }

    private class Reporter(
        override val id: String,
        override val formatId: String,
    ) : ReporterModule {
        override val name = id
        override val version = "1"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun report(
            findings: List<Finding>,
            passed: Boolean,
            config: JsonElement,
        ): String? = null
    }

    private val ruleConfig = JsonObject(mapOf("max" to JsonPrimitive(3)))

    private suspend fun registry() =
        ModuleRegistryImpl().apply {
            register(Rule("late", setOf("c"), defaultPriority = 50, config = ruleConfig))
            register(Rule("early", setOf("a", "bx"), defaultPriority = 10))
            register(Rule("off", setOf("d"), defaultEnabled = false, config = ruleConfig))
            register(Platform("p.slow", 20, "https://slow", authHint = true))
            register(Platform("p.fast", 5, null, authHint = false))
            register(Reporter("r.json", "json"))
            register(Reporter("r.json2", "json"))
            register(Reporter("r.md", "md"))
        }

    @Test
    fun `providers are ordered by priority with their api urls and dsl names`() =
        runTest {
            val registry = registry()

            assertEquals(listOf("p.fast", "p.slow"), ModuleDefaultsCollector.providerOrder(registry))
            assertEquals(mapOf("p.slow" to "https://slow"), ModuleDefaultsCollector.allProviderApiUrls(registry))
            assertEquals(setOf("p.fast-dsl", "p.slow-dsl"), ModuleDefaultsCollector.allAuthDslNames(registry))
            assertEquals("p.slow needs a token", ModuleDefaultsCollector.missingAuthMessage("p.slow", registry))
            assertNull(ModuleDefaultsCollector.missingAuthMessage("p.fast", registry))
            assertNull(ModuleDefaultsCollector.missingAuthMessage("unknown", registry))
        }

    @Test
    fun `rule defaults cover only default enabled rules with a non empty config`() =
        runTest {
            val registry = registry()

            assertEquals(listOf("early", "late"), ModuleDefaultsCollector.defaultEnabledRules(registry).map { it.id })
            assertEquals(mapOf("c" to ruleConfig), ModuleDefaultsCollector.zeroConfigRuleConfigs(registry))
            assertEquals(setOf("a", "bx", "c", "d"), ModuleDefaultsCollector.allRuleIds(registry))
            assertEquals(
                mapOf("a" to "explains a", "c" to "explains c", "d" to "explains d"),
                ModuleDefaultsCollector.ruleExplanations(registry),
            )
            assertEquals(setOf("json", "md"), ModuleDefaultsCollector.allReporterFormats(registry))
        }

    @Test
    fun `runtime initializer passes config and rule prefixes to rule modules`() =
        runTest {
            val rule = Rule("r", setOf("a"))
            val prefixes = mapOf("a" to listOf("jira"))

            RuntimeModuleInitializer().initialize(
                PluginBootstrap().newRunContexts(),
                rule,
                ruleConfig,
                ruleEnrichmentBindingPrefixes = prefixes
            )

            assertEquals(listOf(prefixes), rule.seenPrefixes)
            assertEquals(listOf<JsonElement>(ruleConfig), rule.seenConfigs)
            RuntimeModuleInitializer().initialize(PluginBootstrap().newRunContexts(), Reporter("x", "x"), JsonPrimitive("ignored"))
        }

    @Test
    fun `runtime initializer turns a declined or failing init into a provider error`() =
        runTest {
            val contexts = PluginBootstrap().newRunContexts()
            val declined = assertFailsWith<ProviderException> {
                RuntimeModuleInitializer().initialize(contexts, Rule("r", emptySet(), init = { false }), ruleConfig)
            }
            assertEquals("Selected rule module 'r' is unavailable with current configuration.", declined.message)

            val failing = assertFailsWith<ProviderException> {
                RuntimeModuleInitializer().initialize(contexts, Rule("r", emptySet(), init = { error("boom") }), ruleConfig)
            }
            assertEquals("Failed to initialize rule module 'r': boom", failing.message)

            assertFailsWith<CancellationException> {
                RuntimeModuleInitializer().initialize(
                    contexts,
                    Rule("r", emptySet(), init = { throw CancellationException("stop") }),
                    ruleConfig,
                )
            }
        }

    @Test
    fun `run contexts expose an object config and the run settings`() {
        val contexts = PluginBootstrap().newRunContexts()
        contexts.allowLocalhost = true
        contexts.configDir = "/cfg"

        assertEquals(ruleConfig, contexts.contextWithConfig("m", ruleConfig).pluginConfig)
        assertEquals(JsonObject(emptyMap()), contexts.contextWithConfig("m", JsonPrimitive(1)).pluginConfig)
        assertEquals(JsonObject(emptyMap()), contexts.forModule("m").pluginConfig)
    }
}
