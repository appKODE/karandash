package io.karandash.config.pipeline

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
import io.karandash.config.schema.ConfigSections
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchemaJsonGeneratorTest {
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

    @Test
    fun `generate produces valid JSON Schema structure`() {
        runTest {
            val json = SchemaJsonGenerator(emptyRegistry).generate()

            assertTrue(json.contains("\"\$schema\""))
            assertTrue(json.contains("https://json-schema.org/draft/2020-12/schema"))
            assertTrue(json.contains("\"title\""))
            assertTrue(json.contains("Karandash Configuration"))
            assertTrue(json.contains("\"properties\""))
            assertTrue(json.contains("\"rules\""))
            assertTrue(json.contains("\"platforms\""))
            assertTrue(json.contains("\"reporters\""))
            assertTrue(json.contains("\"extends\""))
            assertTrue(json.contains("\"additionalProperties\": false"))
        }
    }

    /** every top-level config section has a schema entry, and the document stays valid JSON. */
    @Test
    fun `generate covers every config section and parses as JSON`() {
        runTest {
            val json = SchemaJsonGenerator(emptyRegistry).generate()

            val properties = Json
                .parseToJsonElement(json)
                .jsonObject
                .getValue("properties")
                .jsonObject
            assertEquals(emptySet(), ConfigSections.all - properties.keys)
        }
    }

    /** Every key UnknownTopLevelKeys accepts is a schema root property (additionalProperties is false). */
    @Test
    fun `schema root lists every known top-level key`() =
        runTest {
            val properties = Json
                .parseToJsonElement(SchemaJsonGenerator(emptyRegistry).generate())
                .jsonObject
                .getValue("properties")
                .jsonObject

            assertEquals(emptySet(), UnknownTopLevelKeys.known - properties.keys)
        }

    @Test
    fun `generate includes rule IDs from registered rule modules`() {
        runTest {
            val module = stubRuleModule(
                ruleIds = setOf("title-format", "commit-messages"),
                schema = emptyList(),
            )
            val json = SchemaJsonGenerator(registryWithRules(module)).generate()

            assertTrue(json.contains("\"title-format\""))
            assertTrue(json.contains("\"commit-messages\""))
        }
    }

    @Test
    fun `generate includes config property names and types`() {
        runTest {
            val schema = listOf(
                ConfigProperty(
                    name = "pattern",
                    type = ConfigPropertyType.STRING,
                    description = "Regex pattern",
                    required = true,
                ),
                ConfigProperty(
                    name = "enabled",
                    type = ConfigPropertyType.BOOLEAN,
                    description = "Enable the rule",
                ),
                ConfigProperty(
                    name = "maxCount",
                    type = ConfigPropertyType.INT,
                    description = "Max count",
                ),
            )
            val module = stubRuleModule(ruleIds = setOf("my-rule"), schema = schema)
            val json = SchemaJsonGenerator(registryWithRules(module)).generate()

            assertTrue(json.contains("\"pattern\""))
            assertTrue(json.contains("\"type\": \"string\""))
            assertTrue(json.contains("\"enabled\""))
            assertTrue(json.contains("\"type\": \"boolean\""))
            assertTrue(json.contains("\"maxCount\""))
            assertTrue(json.contains("\"type\": \"integer\""))
            assertTrue(json.contains("\"required\""))
            assertTrue(json.contains("\"pattern\""))
        }
    }

    @Test
    fun `generate handles STRING_LIST property type`() {
        runTest {
            val schema = listOf(
                ConfigProperty(name = "tags", type = ConfigPropertyType.STRING_LIST, description = "Tags"),
            )
            val module = stubRuleModule(ruleIds = setOf("my-rule"), schema = schema)
            val json = SchemaJsonGenerator(registryWithRules(module)).generate()

            assertTrue(json.contains("\"type\": \"array\""))
            assertTrue(json.contains("\"items\": {\"type\": \"string\"}"))
        }
    }

    @Test
    fun `generate handles OBJECT_LIST property type`() {
        runTest {
            val schema = listOf(
                ConfigProperty(name = "items", type = ConfigPropertyType.OBJECT_LIST, description = "Items"),
            )
            val module = stubRuleModule(ruleIds = setOf("my-rule"), schema = schema)
            val json = SchemaJsonGenerator(registryWithRules(module)).generate()

            assertTrue(json.contains("\"items\": {\"type\": \"object\"}"))
        }
    }

    @Test
    fun `generate handles ENUM property type with values`() {
        runTest {
            val schema = listOf(
                ConfigProperty(
                    name = "severity",
                    type = ConfigPropertyType.ENUM,
                    description = "Severity level",
                    enumValues = listOf("error", "warning", "info"),
                ),
            )
            val module = stubRuleModule(ruleIds = setOf("my-rule"), schema = schema)
            val json = SchemaJsonGenerator(registryWithRules(module)).generate()

            assertTrue(json.contains("\"enum\""))
            assertTrue(json.contains("\"error\""))
            assertTrue(json.contains("\"warning\""))
            assertTrue(json.contains("\"info\""))
        }
    }

    @Test
    fun `generate escapes double quotes in descriptions`() {
        runTest {
            val schema = listOf(
                ConfigProperty(
                    name = "pattern",
                    type = ConfigPropertyType.STRING,
                    description = "Use \"regex\" syntax",
                ),
            )
            val module = stubRuleModule(ruleIds = setOf("my-rule"), schema = schema)
            val json = SchemaJsonGenerator(registryWithRules(module)).generate()

            assertTrue(json.contains("\\\"regex\\\""))
            assertFalse(json.contains("Use \"regex\" syntax"))
        }
    }

    @Test
    fun `generate with empty registry produces no rule entries`() {
        runTest {
            val json = SchemaJsonGenerator(emptyRegistry).generate()

            assertTrue(json.contains("\"properties\": {\n      }"))
        }
    }

    private fun stubRuleModule(
        ruleIds: Set<String>,
        schema: List<ConfigProperty>,
    ): RuleModule =
        object : RuleModule {
            override val id = "test.stub"
            override val name = "Stub"
            override val version = "1.0.0"

            override suspend fun contributedRules() = ruleIds

            override suspend fun configSchema() = schema

            override suspend fun initialize(context: PluginContext) = true

            override suspend fun validate(
                ruleId: String,
                mr: MergeRequest,
                configElement: JsonElement,
            ): List<Finding> = emptyList()
        }
}
