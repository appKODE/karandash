package io.karandash.config.pipeline

import io.karandash.api.Module
import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.config.ErrorSeverity
import io.karandash.api.module.CliCommandModule
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.ReporterModule
import io.karandash.api.module.RuleModule
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.sdk.typed.TypedPlatformServiceModule
import io.karandash.sdk.typed.TypedReporterModule
import io.karandash.sdk.typed.TypedRuleModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun jsonObj(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*pairs))

private fun jsonStr(value: String): JsonPrimitive = JsonPrimitive(value)

private fun jsonInt(value: Int): JsonPrimitive = JsonPrimitive(value)

private fun jsonBool(value: Boolean): JsonPrimitive = JsonPrimitive(value)

class SchemaCoercerTest {
    // ---- test config type ----

    @Serializable
    data class SimpleConfig(
        val value: String = "default",
        val count: Int = 0,
    )

    /** Config with a required field - strict deserialization fails when field is missing. */
    @Serializable
    data class RequiredFieldConfig(
        val requiredField: String,
    )

    // ---- test module stubs ----

    private class SimpleTypedRuleModule : TypedRuleModule<SimpleConfig> {
        override val configSerializer = SimpleConfig.serializer()
        override val id = "io.karandash.test.simple-rule"
        override val name = "Simple Rule"
        override val version = "1.0.0"

        override suspend fun contributedRules() = setOf("simple-rule")

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ) = emptyList<Finding>()

        override suspend fun validateTyped(
            ruleId: String,
            mr: MergeRequest,
            config: SimpleConfig,
        ) = emptyList<Finding>()
    }

    private class FailingTypedRuleModule : TypedRuleModule<RequiredFieldConfig> {
        override val configSerializer = RequiredFieldConfig.serializer()
        override val id = "io.karandash.test.failing-rule"
        override val name = "Failing Rule"
        override val version = "1.0.0"

        override suspend fun contributedRules() = setOf("failing-rule")

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ) = emptyList<Finding>()

        override suspend fun validateTyped(
            ruleId: String,
            mr: MergeRequest,
            config: RequiredFieldConfig,
        ) = emptyList<Finding>()
    }

    private class PlainRuleModule(
        private val ruleId: String,
    ) : RuleModule {
        override val id = "io.karandash.test.$ruleId"
        override val name = "Plain Rule"
        override val version = "1.0.0"

        override suspend fun contributedRules() = setOf(ruleId)

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ) = emptyList<Finding>()
    }

    // ---- typed reporter stubs ----

    private class SimpleTypedReporterModule : TypedReporterModule<SimpleConfig> {
        override val configSerializer = SimpleConfig.serializer()
        override val id = "io.karandash.test.simple-reporter"
        override val name = "Simple Reporter"
        override val version = "1.0.0"
        override val formatId = "simple-reporter"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun reportTyped(
            findings: List<Finding>,
            passed: Boolean,
            config: SimpleConfig,
        ): String? = null
    }

    private class FailingTypedReporterModule : TypedReporterModule<RequiredFieldConfig> {
        override val configSerializer = RequiredFieldConfig.serializer()
        override val id = "io.karandash.test.failing-reporter"
        override val name = "Failing Reporter"
        override val version = "1.0.0"
        override val formatId = "failing-reporter"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun reportTyped(
            findings: List<Finding>,
            passed: Boolean,
            config: RequiredFieldConfig,
        ): String? = null
    }

    // ---- typed provider stub ----

    private class SimpleTypedProviderModule : TypedPlatformServiceModule<SimpleConfig> {
        override val configSerializer = SimpleConfig.serializer()
        override val id = "io.karandash.test.simple-provider"
        override val name = "Simple Provider"
        override val version = "1.0.0"
        override val configKey = "simple-provider"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun initializeTyped(
            context: PluginContext,
            config: SimpleConfig,
        ) = true

        override suspend fun fetchMergeRequest(
            projectId: String,
            mrId: String,
        ) = throw UnsupportedOperationException("test stub")

        override suspend fun fetchMergeRequestByUrl(url: String) = throw UnsupportedOperationException("test stub")

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
    }

    // ---- typed comment poster stub ----

    private class SimpleTypedPlatformServiceModule : TypedPlatformServiceModule<SimpleConfig> {
        override val configSerializer = SimpleConfig.serializer()
        override val id = "io.karandash.test.simple-poster"
        override val name = "Simple Poster"
        override val version = "1.0.0"
        override val configKey = "simple-poster"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun initializeTyped(
            context: PluginContext,
            config: SimpleConfig,
        ) = true

        override suspend fun fetchMergeRequest(
            projectId: String,
            mrId: String,
        ): MergeRequest = error("not implemented")

        override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = error("not implemented")

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
            diffRefs: io.karandash.models.DiffRefs,
            startLine: Int?,
        ) = ""
    }

    // ---- typed mutator stub ----

    private class SimpleTypedMutatorModule : TypedPlatformServiceModule<SimpleConfig> {
        override val configSerializer = SimpleConfig.serializer()
        override val id = "io.karandash.test.simple-mutator"
        override val name = "Simple Mutator"
        override val version = "1.0.0"
        override val configKey = "simple-mutator"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun initializeTyped(
            context: PluginContext,
            config: SimpleConfig,
        ) = true

        override suspend fun fetchMergeRequest(
            projectId: String,
            mrId: String,
        ): MergeRequest = error("not implemented")

        override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = error("not implemented")

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
            mrId: String
        ) = Unit

        override suspend fun unapprove(
            projectId: String,
            mrId: String
        ) = Unit

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
    }

    private fun fakeRegistry(
        rules: List<RuleModule> = emptyList(),
        reporters: List<ReporterModule> = emptyList(),
        platformServices: List<PlatformServiceModule> = emptyList(),
    ): ModuleRegistry =
        object : ModuleRegistry {
            override suspend fun register(module: Module) = Unit

            override suspend fun unregister(id: String) = false

            override fun get(id: String): Module? = null

            override fun getByType(type: ModuleType): List<Module> = emptyList()

            override fun ruleModules(): List<RuleModule> = rules

            override fun platformServices(): List<PlatformServiceModule> = platformServices

            override fun reporters(): List<ReporterModule> = reporters

            override fun registeredIds(): Set<String> = rules.map { it.id }.toSet()

            override fun hasRule(ruleId: String) = rules.any { it.id == ruleId }

            override fun findRuleHandler(ruleId: String) = rules.firstOrNull { it.id == ruleId }

            override fun findByAlias(alias: String): PlatformServiceModule? = null

            override fun allAliases(): Map<String, PlatformServiceModule> = emptyMap()

            override fun aliasCollisions(): List<String> = emptyList()

            override fun cliCommandModules(): List<CliCommandModule> = emptyList()

            override fun aiProviders(): List<io.karandash.api.ai.AiProviderModule> = emptyList()

            override fun aiInterceptors(): List<io.karandash.api.ai.AiInterceptorModule> = emptyList()

            override fun findAiProvider(providerId: String): io.karandash.api.ai.AiProviderModule? = null
        }

    // ---- tests ----

    @Test
    fun `coerce empty element returns empty CoercedResult with no errors`() {
        runTest {
            val result = SchemaCoercer(fakeRegistry()).coerce(JsonObject(emptyMap()))
            assertTrue(result.rules.isEmpty())
            assertTrue(result.warnings.isEmpty())
            assertTrue(result.errors.isEmpty())
        }
    }

    @Test
    fun `coerce passes through non-rules sections unchanged`() {
        runTest {
            val rawElement = jsonObj(
                "platforms" to jsonObj("gitlab" to jsonObj("url" to jsonStr("https://gitlab.com"))),
                "reporters" to jsonObj("console" to jsonBool(true)),
            )
            val result = SchemaCoercer(fakeRegistry()).coerce(rawElement)
            assertEquals(jsonObj("url" to jsonStr("https://gitlab.com")), result.platforms["gitlab"])
            assertEquals(jsonBool(true), result.reporters["console"])
        }
    }

    @Test
    fun `coerce passes through valid typed module rule config without warnings`() {
        runTest {
            val registry = fakeRegistry(listOf(SimpleTypedRuleModule()))
            val rawElement = jsonObj("rules" to jsonObj("simple-rule" to jsonObj("value" to jsonStr("hello"))))
            val result = SchemaCoercer(registry).coerce(rawElement)
            assertTrue(result.warnings.isEmpty())
            assertEquals(jsonObj("value" to jsonStr("hello")), result.rules["simple-rule"])
        }
    }

    @Test
    fun `a field whose whole value is an env placeholder is checked after interpolation not before`() {
        runTest {
            val registry = fakeRegistry(listOf(SimpleTypedRuleModule()))
            val section = jsonObj("count" to jsonStr("\${LEN}"), "value" to jsonStr("\${NAME:-x}"))
            val result = SchemaCoercer(registry).coerce(jsonObj("rules" to jsonObj("simple-rule" to section)))
            assertTrue(result.warnings.isEmpty(), "${result.warnings}")
            assertEquals(section, result.rules["simple-rule"])

            val mixed = jsonObj("rules" to jsonObj("simple-rule" to jsonObj("count" to jsonStr("n\${LEN}"))))
            assertEquals(1, SchemaCoercer(registry).coerce(mixed).warnings.size, "a value that is more than a placeholder is still checked")
        }
    }

    @Test
    fun `coerce emits warning when typed module schema validation fails`() {
        runTest {
            val registry = fakeRegistry(listOf(FailingTypedRuleModule()))
            val rawElement = jsonObj("rules" to jsonObj("failing-rule" to jsonObj("bad" to jsonStr("data"))))
            val result = SchemaCoercer(registry).coerce(rawElement)
            assertEquals(1, result.warnings.size)
            assertEquals(ErrorSeverity.WARNING, result.warnings[0].severity)
            assertEquals("failing-rule", result.warnings[0].moduleId)
            assertTrue(
                result.warnings[0].message.contains("requiredField"),
                "Warning message should include the missing field name",
            )
            assertTrue(result.rules.containsKey("failing-rule"))
        }
    }

    @Test
    fun `coerce passes through unknown rule without warning`() {
        runTest {
            val result = SchemaCoercer(fakeRegistry()).coerce(
                jsonObj("rules" to jsonObj("unknown-rule" to jsonObj("x" to jsonInt(1)))),
            )
            assertTrue(result.warnings.isEmpty())
            assertTrue(result.rules.containsKey("unknown-rule"))
        }
    }

    @Test
    fun `coerce passes through plain RuleModule config without modification`() {
        runTest {
            val registry = fakeRegistry(listOf(PlainRuleModule("plain-rule")))
            val rawElement = jsonObj("rules" to jsonObj("plain-rule" to jsonObj("key" to jsonStr("value"))))
            val result = SchemaCoercer(registry).coerce(rawElement)
            assertTrue(result.warnings.isEmpty())
            assertEquals(jsonObj("key" to jsonStr("value")), result.rules["plain-rule"])
        }
    }

    @Test
    fun `coerce emits warning for rule with non-map config value`() {
        runTest {
            val registry = fakeRegistry(listOf(PlainRuleModule("some-rule")))
            val rawElement = jsonObj("rules" to jsonObj("some-rule" to jsonStr("invalid-string")))
            val result = SchemaCoercer(registry).coerce(rawElement)
            assertEquals(1, result.warnings.size)
            assertEquals(ErrorSeverity.WARNING, result.warnings[0].severity)
        }
    }

    @Test
    fun `coerce handles null rule config as empty object`() {
        runTest {
            val registry = fakeRegistry(listOf(PlainRuleModule("nullable-rule")))
            val rawElement = jsonObj("rules" to jsonObj("nullable-rule" to JsonNull))
            val result = SchemaCoercer(registry).coerce(rawElement)
            assertTrue(result.warnings.isEmpty())
            assertEquals(JsonObject(emptyMap()), result.rules["nullable-rule"])
        }
    }

    @Test
    fun `coerce handles multiple rules with mixed typed and plain modules`() {
        runTest {
            val registry = fakeRegistry(listOf(SimpleTypedRuleModule(), PlainRuleModule("plain-rule")))
            val rawElement = jsonObj(
                "rules" to jsonObj(
                    "simple-rule" to jsonObj("value" to jsonStr("typed-value")),
                    "plain-rule" to jsonObj("key" to jsonStr("plain-value")),
                    "unknown-rule" to jsonObj("x" to jsonInt(1)),
                ),
            )
            val result = SchemaCoercer(registry).coerce(rawElement)
            assertTrue(result.warnings.isEmpty(), "No warnings expected for valid configs")
            assertEquals(jsonObj("value" to jsonStr("typed-value")), result.rules["simple-rule"])
            assertEquals(jsonObj("key" to jsonStr("plain-value")), result.rules["plain-rule"])
            assertEquals(jsonObj("x" to jsonInt(1)), result.rules["unknown-rule"])
            assertEquals(3, result.rules.size)
        }
    }

    @Test
    fun `coerce treats non-object rules section as empty`() {
        runTest {
            val result = SchemaCoercer(fakeRegistry()).coerce(jsonObj("rules" to jsonStr("not-a-map")))
            assertTrue(result.rules.isEmpty(), "Non-object rules section should be silently treated as empty")
            assertTrue(result.warnings.isEmpty())
        }
    }

    @Test
    fun `coerce validates typed reporter config and emits no warning when valid`() {
        runTest {
            val registry = fakeRegistry(reporters = listOf(SimpleTypedReporterModule()))
            val rawElement = jsonObj("reporters" to jsonObj("simple-reporter" to jsonObj("value" to jsonStr("hello"))))
            val result = SchemaCoercer(registry).coerce(rawElement)
            assertTrue(result.warnings.isEmpty())
            assertEquals(jsonObj("value" to jsonStr("hello")), result.reporters["simple-reporter"])
        }
    }

    @Test
    fun `coerce emits warning when typed reporter schema validation fails`() {
        runTest {
            val registry = fakeRegistry(reporters = listOf(FailingTypedReporterModule()))
            val rawElement = jsonObj("reporters" to jsonObj("failing-reporter" to jsonObj("bad" to jsonStr("data"))))
            val result = SchemaCoercer(registry).coerce(rawElement)
            assertEquals(1, result.warnings.size)
            assertEquals(ErrorSeverity.WARNING, result.warnings[0].severity)
            assertEquals("failing-reporter", result.warnings[0].moduleId)
            assertTrue(result.warnings[0].message.contains("requiredField"))
            assertTrue(result.reporters.containsKey("failing-reporter"))
        }
    }

    @Test
    fun `coerce passes through boolean reporter config without validation`() {
        runTest {
            val registry = fakeRegistry(reporters = listOf(SimpleTypedReporterModule()))
            val rawElement = jsonObj("reporters" to jsonObj("simple-reporter" to jsonBool(true)))
            val result = SchemaCoercer(registry).coerce(rawElement)
            assertTrue(result.warnings.isEmpty())
            assertEquals(jsonBool(true), result.reporters["simple-reporter"])
        }
    }

    @Test
    fun `coerce passes through null reporter config without validation`() {
        runTest {
            val registry = fakeRegistry(reporters = listOf(SimpleTypedReporterModule()))
            val rawElement = jsonObj("reporters" to jsonObj("simple-reporter" to JsonNull))
            val result = SchemaCoercer(registry).coerce(rawElement)
            assertTrue(result.warnings.isEmpty())
            assertEquals(JsonNull, result.reporters["simple-reporter"])
        }
    }

    @Test
    fun `coerce emits warning for reporter with non-object non-boolean config`() {
        runTest {
            val registry = fakeRegistry(reporters = listOf(SimpleTypedReporterModule()))
            val rawElement = jsonObj("reporters" to jsonObj("simple-reporter" to jsonInt(42)))
            val result = SchemaCoercer(registry).coerce(rawElement)
            assertEquals(1, result.warnings.size)
            assertEquals(ErrorSeverity.WARNING, result.warnings[0].severity)
        }
    }

    @Test
    fun `coerce validates typed provider config and emits no warning when valid`() {
        runTest {
            val registry = fakeRegistry(platformServices = listOf(SimpleTypedProviderModule()))
            val rawElement = jsonObj("platforms" to jsonObj("simple-provider" to jsonObj("value" to jsonStr("token"))))
            val result = SchemaCoercer(registry).coerce(rawElement)
            assertTrue(result.warnings.isEmpty())
            assertEquals(jsonObj("value" to jsonStr("token")), result.platforms["simple-provider"])
        }
    }

    @Test
    fun `coerce validates typed comment poster config and emits no warning when valid`() {
        runTest {
            val registry = fakeRegistry(platformServices = listOf(SimpleTypedPlatformServiceModule()))
            val rawElement = jsonObj("platforms" to jsonObj("simple-poster" to jsonObj("value" to jsonStr("x"))))
            val result = SchemaCoercer(registry).coerce(rawElement)
            assertTrue(result.warnings.isEmpty())
            assertEquals(jsonObj("value" to jsonStr("x")), result.platforms["simple-poster"])
        }
    }

    @Test
    fun `coerce validates typed mutator config and emits no warning when valid`() {
        runTest {
            val registry = fakeRegistry(platformServices = listOf(SimpleTypedMutatorModule()))
            val rawElement = jsonObj("platforms" to jsonObj("simple-mutator" to jsonObj("value" to jsonStr("y"))))
            val result = SchemaCoercer(registry).coerce(rawElement)
            assertTrue(result.warnings.isEmpty())
            assertEquals(jsonObj("value" to jsonStr("y")), result.platforms["simple-mutator"])
        }
    }

    @Test
    fun `coerce passes through unknown reporter config unchanged`() {
        runTest {
            val result = SchemaCoercer(fakeRegistry()).coerce(
                jsonObj("reporters" to jsonObj("unknown-reporter" to jsonObj("x" to jsonInt(1)))),
            )
            assertTrue(result.warnings.isEmpty())
            assertTrue(result.reporters.containsKey("unknown-reporter"))
        }
    }
}
