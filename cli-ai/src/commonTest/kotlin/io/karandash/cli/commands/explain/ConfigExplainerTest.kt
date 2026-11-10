package io.karandash.cli.commands.explain

import io.karandash.api.Module
import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.config.ConfigProperty
import io.karandash.api.config.ConfigPropertyType
import io.karandash.api.module.CliCommandModule
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.ReporterModule
import io.karandash.api.module.RuleModule
import io.karandash.api.support.mapToJsonElement
import io.karandash.config.Config
import io.karandash.config.RulesConfig
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigExplainerTest {
    private val emptyRegistry = object : ModuleRegistry {
        override suspend fun register(module: Module) = Unit

        override suspend fun unregister(id: String) = false

        override fun get(id: String): Module? = null

        override fun getByType(type: ModuleType): List<Module> = emptyList()

        override fun ruleModules(): List<RuleModule> = emptyList()

        override fun platformServices(): List<PlatformServiceModule> = emptyList()

        override fun reporters(): List<ReporterModule> = emptyList()

        override fun registeredIds(): Set<String> = emptySet()

        override fun hasRule(ruleId: String) = false

        override fun findRuleHandler(ruleId: String): RuleModule? = null

        override fun findByAlias(alias: String): PlatformServiceModule? = null

        override fun allAliases(): Map<String, PlatformServiceModule> = emptyMap()

        override fun aliasCollisions(): List<String> = emptyList()

        override fun cliCommandModules(): List<CliCommandModule> = emptyList()

        override fun aiProviders(): List<io.karandash.api.ai.AiProviderModule> = emptyList()

        override fun aiInterceptors(): List<io.karandash.api.ai.AiInterceptorModule> = emptyList()

        override fun findAiProvider(providerId: String): io.karandash.api.ai.AiProviderModule? = null
    }

    private fun registryWithRules(vararg modules: RuleModule): ModuleRegistry =
        object : ModuleRegistry by emptyRegistry {
            override fun ruleModules(): List<RuleModule> = modules.toList()
        }

    private fun stubRuleModule(
        ruleIds: Set<String>,
        schema: List<ConfigProperty> = emptyList(),
        defaults: Map<String, Any?> = emptyMap(),
        description: String = "Test rule",
        isDefault: Boolean = false,
    ): RuleModule =
        object : RuleModule {
            override val id = "test.${ruleIds.first()}"
            override val name = "Test ${ruleIds.first()}"
            override val version = "1.0.0"
            override val description = description
            override val defaultEnabled = isDefault

            override suspend fun contributedRules() = ruleIds

            override suspend fun configSchema() = schema

            override suspend fun defaultConfig(): JsonElement = mapToJsonElement(defaults)

            override suspend fun initialize(context: PluginContext) = true

            override suspend fun validate(
                ruleId: String,
                mr: MergeRequest,
                configElement: JsonElement,
            ): List<Finding> = emptyList()
        }

    // --- Core explain logic ---

    @Test
    fun `explain with empty config and empty registry produces empty result`() {
        runTest {
            val explainer = ConfigExplainer(emptyRegistry)
            val result = explainer.explain(Config())

            assertTrue(result.configuredModules.isEmpty())
            assertTrue(result.availableModules.isEmpty())
            assertTrue(result.isHealthy)
        }
    }

    @Test
    fun `explain reports configured rule with valid fields`() {
        runTest {
            val schema = listOf(
                ConfigProperty("pattern", ConfigPropertyType.STRING, "Regex pattern"),
                ConfigProperty("minLength", ConfigPropertyType.INT, "Min length"),
            )
            val module = stubRuleModule(
                ruleIds = setOf("title-format"),
                schema = schema,
                defaults = mapOf("pattern" to ".*", "minLength" to 10),
            )
            val registry = registryWithRules(module)
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "title-format" to JsonObject(mapOf("pattern" to JsonPrimitive("^feat:"), "minLength" to JsonPrimitive(5)))
                    )
                )
            )

            val result = ConfigExplainer(registry).explain(config)

            assertEquals(1, result.configuredModules.size)
            val report = result.configuredModules.first()
            assertEquals("title-format", report.moduleId)
            assertEquals("Rule", report.moduleType)
            assertTrue(report.isConfigured)
            assertTrue(report.isHealthy)
            assertEquals(2, report.fields.size)
            assertTrue(report.fields.all { it.isSet && it.isValid })
        }
    }

    @Test
    fun `explain detects type mismatch`() {
        runTest {
            val schema = listOf(
                ConfigProperty("minLength", ConfigPropertyType.INT, "Min length"),
            )
            val module = stubRuleModule(ruleIds = setOf("my-rule"), schema = schema)
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf("my-rule" to JsonObject(mapOf("minLength" to JsonPrimitive("not-a-number"))))
                )
            )

            val result = ConfigExplainer(registryWithRules(module)).explain(config)

            val report = result.configuredModules.first()
            assertFalse(report.isHealthy)
            val field = report.fields.first()
            assertFalse(field.isValid)
            assertTrue(field.issue!!.contains("Expected integer"))
        }
    }

    @Test
    fun `explain detects unknown keys with typo suggestion`() {
        runTest {
            val schema = listOf(
                ConfigProperty("pattern", ConfigPropertyType.STRING, "Regex pattern"),
            )
            val module = stubRuleModule(ruleIds = setOf("my-rule"), schema = schema)
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf("my-rule" to JsonObject(mapOf("patttern" to JsonPrimitive("^feat:"))))
                )
            )

            val result = ConfigExplainer(registryWithRules(module)).explain(config)

            val report = result.configuredModules.first()
            assertFalse(report.isHealthy)
            assertEquals(1, report.unknownKeys.size)
            assertEquals("patttern", report.unknownKeys.first().key)
            assertEquals("pattern", report.unknownKeys.first().suggestion)
        }
    }

    @Test
    fun `explain detects missing required fields`() {
        runTest {
            val schema = listOf(
                ConfigProperty("projectKeys", ConfigPropertyType.STRING_LIST, "JIRA project keys", required = true),
            )
            val module = stubRuleModule(ruleIds = setOf("jira-ticket"), schema = schema)
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf("jira-ticket" to JsonObject(mapOf("someOther" to JsonPrimitive("value"))))
                )
            )

            val result = ConfigExplainer(registryWithRules(module)).explain(config)

            val report = result.configuredModules.first()
            assertFalse(report.isHealthy)
            assertEquals(1, report.missingRequired.size)
            assertEquals("projectKeys", report.missingRequired.first().name)
        }
    }

    @Test
    fun `explain lists unconfigured rules as available`() {
        runTest {
            val module = stubRuleModule(ruleIds = setOf("security"), description = "Security checks")
            val config = Config()

            val result = ConfigExplainer(registryWithRules(module)).explain(config)

            assertTrue(result.configuredModules.isEmpty())
            assertEquals(1, result.availableModules.size)
            assertEquals("security", result.availableModules.first().moduleId)
            assertFalse(result.availableModules.first().isConfigured)
        }
    }

    @Test
    fun `explain marks default-enabled unconfigured rules`() {
        runTest {
            val module = stubRuleModule(
                ruleIds = setOf("draft"),
                description = "Draft detection",
                isDefault = true,
            )
            val config = Config()

            val result = ConfigExplainer(registryWithRules(module)).explain(config)

            assertEquals(1, result.availableModules.size)
            assertTrue(result.availableModules.first().isDefaultEnabled)
        }
    }

    @Test
    fun `explain reports unknown rule IDs not in registry`() {
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf("nonexistent-rule" to JsonObject(mapOf("foo" to JsonPrimitive("bar"))))
                )
            )

            val result = ConfigExplainer(emptyRegistry).explain(config)

            assertEquals(1, result.configuredModules.size)
            val report = result.configuredModules.first()
            assertEquals("nonexistent-rule", report.moduleId)
            assertTrue(report.description.contains("Unknown"))
        }
    }

    @Test
    fun `explain matches a kebab rule id to its camelCase configured key`() {
        runTest {
            val module = stubRuleModule(ruleIds = setOf("go-title-length"))
            val config = Config(
                rules = RulesConfig(ruleConfigs = mapOf("goTitleLength" to JsonObject(emptyMap()))),
            )

            val result = ConfigExplainer(registryWithRules(module)).explain(config)

            assertEquals(listOf("go-title-length"), result.configuredModules.map { it.moduleId })
            assertTrue(result.availableModules.isEmpty())
        }
    }

    @Test
    fun `explain shows default values for unset fields`() {
        runTest {
            val schema = listOf(
                ConfigProperty("pattern", ConfigPropertyType.STRING, "Regex", defaultValue = ".*"),
                ConfigProperty("maxLength", ConfigPropertyType.INT, "Max"),
            )
            val module = stubRuleModule(
                ruleIds = setOf("title-format"),
                schema = schema,
                defaults = mapOf("pattern" to ".*"),
            )
            val config = Config(
                rules = RulesConfig(ruleConfigs = mapOf("title-format" to JsonObject(emptyMap())))
            )

            val result = ConfigExplainer(registryWithRules(module)).explain(config)

            val report = result.configuredModules.first()
            val patternField = report.fields.first { it.property.name == "pattern" }
            assertFalse(patternField.isSet)
            assertEquals(".*", patternField.actualValue)
            assertTrue(patternField.isValid)

            val maxField = report.fields.first { it.property.name == "maxLength" }
            assertFalse(maxField.isSet)
            assertNull(maxField.actualValue)
        }
    }

    @Test
    fun `explain ignores meta keys in unknown key detection`() {
        runTest {
            val schema = listOf(
                ConfigProperty("pattern", ConfigPropertyType.STRING, "Regex"),
            )
            val module = stubRuleModule(ruleIds = setOf("my-rule"), schema = schema)
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "my-rule" to JsonObject(
                            mapOf(
                                "pattern" to JsonPrimitive("^feat:"),
                                "priority" to JsonPrimitive(50),
                                "bail-on-fail" to JsonPrimitive(true),
                                "when" to JsonPrimitive("not(is_draft)"),
                                "mode" to JsonPrimitive("normal"),
                            )
                        )
                    )
                )
            )

            val result = ConfigExplainer(registryWithRules(module)).explain(config)

            val report = result.configuredModules.first()
            assertTrue(report.unknownKeys.isEmpty())
            assertTrue(report.isHealthy)
        }
    }

    // --- Type validation ---

    @Test
    fun `validateType accepts valid string`() {
        val prop = ConfigProperty("p", ConfigPropertyType.STRING, "")
        assertNull(ConfigExplainer.validateType(prop, "hello"))
    }

    @Test
    fun `validateType rejects non-string for STRING type`() {
        val prop = ConfigProperty("p", ConfigPropertyType.STRING, "")
        assertTrue(ConfigExplainer.validateType(prop, 42)!!.contains("Expected string"))
    }

    @Test
    fun `validateType accepts valid int`() {
        val prop = ConfigProperty("p", ConfigPropertyType.INT, "")
        assertNull(ConfigExplainer.validateType(prop, 42))
    }

    @Test
    fun `validateType rejects non-number for INT type`() {
        val prop = ConfigProperty("p", ConfigPropertyType.INT, "")
        assertTrue(ConfigExplainer.validateType(prop, "text")!!.contains("Expected integer"))
    }

    @Test
    fun `validateType accepts valid boolean`() {
        val prop = ConfigProperty("p", ConfigPropertyType.BOOLEAN, "")
        assertNull(ConfigExplainer.validateType(prop, true))
    }

    @Test
    fun `validateType rejects non-boolean for BOOLEAN type`() {
        val prop = ConfigProperty("p", ConfigPropertyType.BOOLEAN, "")
        assertTrue(ConfigExplainer.validateType(prop, "yes")!!.contains("Expected boolean"))
    }

    @Test
    fun `validateType accepts valid string list`() {
        val prop = ConfigProperty("p", ConfigPropertyType.STRING_LIST, "")
        assertNull(ConfigExplainer.validateType(prop, listOf("a", "b")))
    }

    @Test
    fun `validateType rejects non-list for STRING_LIST type`() {
        val prop = ConfigProperty("p", ConfigPropertyType.STRING_LIST, "")
        assertTrue(ConfigExplainer.validateType(prop, "not-a-list")!!.contains("Expected list"))
    }

    @Test
    fun `validateType rejects list with non-string elements`() {
        val prop = ConfigProperty("p", ConfigPropertyType.STRING_LIST, "")
        assertTrue(ConfigExplainer.validateType(prop, listOf("a", 42))!!.contains("all list elements"))
    }

    @Test
    fun `validateType accepts valid enum value`() {
        val prop = ConfigProperty("p", ConfigPropertyType.ENUM, "", enumValues = listOf("a", "b", "c"))
        assertNull(ConfigExplainer.validateType(prop, "b"))
    }

    @Test
    fun `validateType rejects invalid enum value`() {
        val prop = ConfigProperty("p", ConfigPropertyType.ENUM, "", enumValues = listOf("a", "b"))
        assertTrue(ConfigExplainer.validateType(prop, "z")!!.contains("Invalid value"))
    }

    @Test
    fun `validateType accepts valid object`() {
        val prop = ConfigProperty("p", ConfigPropertyType.OBJECT, "")
        assertNull(ConfigExplainer.validateType(prop, mapOf("k" to "v")))
    }

    @Test
    fun `validateType accepts valid object list`() {
        val prop = ConfigProperty("p", ConfigPropertyType.OBJECT_LIST, "")
        assertNull(ConfigExplainer.validateType(prop, listOf(mapOf("k" to "v"))))
    }

    @Test
    fun `validateType returns null for null values`() {
        val prop = ConfigProperty("p", ConfigPropertyType.STRING, "")
        assertNull(ConfigExplainer.validateType(prop, null))
    }

    // --- Levenshtein distance ---

    @Test
    fun `findClosestMatch returns suggestion for close match`() {
        val candidates = setOf("pattern", "minLength", "maxLength")
        assertEquals("pattern", ConfigExplainer.findClosestMatch("patttern", candidates))
        assertEquals("pattern", ConfigExplainer.findClosestMatch("patern", candidates))
    }

    @Test
    fun `findClosestMatch returns null for distant strings`() {
        val candidates = setOf("pattern", "minLength")
        assertNull(ConfigExplainer.findClosestMatch("completely_different", candidates))
    }

    // --- Renderer smoke tests ---

    @Test
    fun `TextExplainRenderer produces readable output`() {
        val result = ExplainResult(
            configPath = "test.yaml",
            configuredModules = listOf(
                ModuleReport(
                    moduleId = "title-format",
                    moduleType = "Rule",
                    description = "Title format validation",
                    isConfigured = true,
                    isDefaultEnabled = false,
                    fields = listOf(
                        FieldReport(
                            property = ConfigProperty("pattern", ConfigPropertyType.STRING, "Regex"),
                            actualValue = "^feat:",
                            isSet = true,
                            isValid = true,
                        )
                    ),
                    unknownKeys = emptyList(),
                    missingRequired = emptyList(),
                )
            ),
            availableModules = emptyList(),
            globalIssues = emptyList(),
        )

        val output = TextExplainRenderer.render(result)
        assertTrue(output.contains("title-format"))
        assertTrue(output.contains("pattern"))
        assertTrue(output.contains("[v]"))
        assertTrue(output.contains("no issues found"))
    }

    @Test
    fun `MarkdownExplainRenderer produces valid markdown`() {
        val result = ExplainResult(
            configPath = "test.yaml",
            configuredModules = listOf(
                ModuleReport(
                    moduleId = "my-rule",
                    moduleType = "Rule",
                    description = "A test rule",
                    isConfigured = true,
                    isDefaultEnabled = false,
                    fields = listOf(
                        FieldReport(
                            property = ConfigProperty("enabled", ConfigPropertyType.BOOLEAN, "Enabled"),
                            actualValue = true,
                            isSet = true,
                            isValid = true,
                        )
                    ),
                    unknownKeys = listOf(UnknownKeyReport("typo", "enabled")),
                    missingRequired = emptyList(),
                )
            ),
            availableModules = emptyList(),
            globalIssues = emptyList(),
        )

        val output = MarkdownExplainRenderer.render(result)
        assertTrue(output.contains("# Karandash Configuration Report"))
        assertTrue(output.contains("| Property | Value | Type | Status |"))
        assertTrue(output.contains("`my-rule`"))
        assertTrue(output.contains("did you mean `enabled`"))
    }

    @Test
    fun `JsonExplainRenderer produces valid JSON structure`() {
        val result = ExplainResult(
            configPath = null,
            configuredModules = emptyList(),
            availableModules = emptyList(),
            globalIssues = emptyList(),
        )

        val output = JsonExplainRenderer.render(result)
        assertTrue(output.contains("\"configPath\": null"))
        assertTrue(output.contains("\"healthy\": true"))
        assertTrue(output.contains("\"configuredModules\": ["))
        assertTrue(output.contains("\"availableModules\": ["))
    }

    @Test
    fun `JsonExplainRenderer includes field details`() {
        val result = ExplainResult(
            configPath = "config.yaml",
            configuredModules = listOf(
                ModuleReport(
                    moduleId = "test",
                    moduleType = "Rule",
                    description = "Test",
                    isConfigured = true,
                    isDefaultEnabled = false,
                    fields = listOf(
                        FieldReport(
                            property = ConfigProperty("count", ConfigPropertyType.INT, "Count", defaultValue = 5),
                            actualValue = "wrong",
                            isSet = true,
                            isValid = false,
                            issue = "Expected integer, got string",
                        )
                    ),
                    unknownKeys = emptyList(),
                    missingRequired = emptyList(),
                )
            ),
            availableModules = emptyList(),
            globalIssues = emptyList(),
        )

        val output = JsonExplainRenderer.render(result)
        assertTrue(output.contains("\"healthy\": false"))
        assertTrue(output.contains("\"valid\": false"))
        assertTrue(output.contains("Expected integer"))
    }
}
