package io.karandash.sdk

import io.karandash.api.InternalKarandashApi
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.ReportMetadata
import io.karandash.api.finding.ValidationReport
import io.karandash.api.finding.summarize
import io.karandash.api.platform.PlatformCloseable
import io.karandash.api.platform.UnsupportedPlatformOperationException
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.sdk.config.ConfigFormatModuleAdapter
import io.karandash.sdk.context.BridgeHostSettings
import io.karandash.sdk.finding.MessageCode
import io.karandash.sdk.module.AbstractFormatModule
import io.karandash.sdk.module.AbstractOutputModule
import io.karandash.sdk.module.AbstractProfileModule
import io.karandash.sdk.module.AbstractReporterModule
import io.karandash.sdk.module.AbstractRuleModule
import io.karandash.sdk.module.ConfigFormatModule
import io.karandash.sdk.typed.AbstractTypedEnrichedReporterModule
import io.karandash.sdk.typed.AbstractTypedFormatModule
import io.karandash.sdk.typed.AbstractTypedMultiRuleModule
import io.karandash.sdk.typed.AbstractTypedOutputModule
import io.karandash.sdk.typed.AbstractTypedPlatformServiceModule
import io.karandash.utils.mr.ShortSha
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SdkModuleBasesTest {
    @Serializable
    data class Cfg(
        val level: Int = 1,
    )

    @Serializable
    data class RequiredCfg(
        val key: String,
    )

    private class Context(
        override val pluginConfig: JsonElement = JsonObject(emptyMap()),
    ) : PluginContext {
        val warnings = mutableListOf<String>()
        override val hostVersion: String = "0.0.0"
        override val workingDirectory: String = "."

        override fun env(name: String): String? = null

        override fun log(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }
    }

    private class Platform(
        private val onInit: (Cfg) -> Boolean = { true },
    ) : AbstractTypedPlatformServiceModule<Cfg>(Cfg.serializer()) {
        override val id = "test.platform"
        override val name = "Test"
        override val version = "0"
        var hint: String? = null

        override suspend fun authTokenHint(): String? = hint

        override suspend fun initializeTyped(
            context: PluginContext,
            config: Cfg,
        ): Boolean = onInit(config)

        fun manage(closeable: PlatformCloseable?) = setManagedCloseable(closeable)

        suspend fun <R> withClient(
            client: String?,
            block: suspend (String) -> R,
        ): R = withInitializedClient(client, "Test", block)

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
    }

    private val report = ValidationReport(
        passed = true,
        findings = emptyList(),
        summary = emptyList<Finding>().summarize(),
        groupedByLocation = emptyMap(),
        inlineEligible = emptyList(),
        summaryOnly = emptyList(),
        complianceChecklist = emptyList(),
        explanations = emptyMap(),
        metadata = ReportMetadata("1", 0),
    )

    @Test
    fun `platform base rejects fetches and mutations it does not implement`() =
        runTest {
            val platform = Platform()
            assertEquals(
                "test.platform does not support fetching merge requests",
                assertFailsWith<IllegalStateException> { platform.fetchMergeRequest("p", "1") }.message,
            )
            assertFailsWith<IllegalStateException> { platform.fetchMergeRequestByUrl("u") }
            val mutations: List<suspend () -> Unit> = listOf(
                { platform.addLabels("p", "1", listOf("l")) },
                { platform.removeLabels("p", "1", listOf("l")) },
                { platform.setDraft("p", "1", true) },
                { platform.updateTitle("p", "1", "t") },
                { platform.updateDescription("p", "1", "d") },
                { platform.approve("p", "1") },
                { platform.unapprove("p", "1") },
            )
            mutations.forEach { mutation ->
                val error = assertFailsWith<UnsupportedPlatformOperationException> { mutation() }
                assertEquals("${error.operation} is not supported by this platform", error.message)
            }
        }

    @Test
    fun `platform base warns about missing auth only when a hint exists`() =
        runTest {
            val platform = Platform()
            assertEquals(emptyList(), platform.messages())

            platform.hint = "TEST_TOKEN"
            val message = platform.messages().single()
            assertEquals(MessageCode.MISSING_AUTH, message.code)
            assertTrue("Set TEST_TOKEN" in message.text, message.text)
        }

    @Test
    fun `platform base closes the managed resource once`() =
        runTest {
            val platform = Platform()
            val closed = mutableListOf<String>()
            platform.manage { closed += "a" }

            platform.shutdown()
            platform.shutdown()
            assertEquals(listOf("a"), closed)
        }

    @Test
    fun `initialized client guard fails with the platform name`() =
        runTest {
            val platform = Platform()
            assertEquals("ok:c", platform.withClient("c") { "ok:$it" })
            assertEquals(
                "Test not initialized",
                assertFailsWith<IllegalStateException> { platform.withClient(null) { it } }.message,
            )
        }

    @Test
    fun `typed platform warns on bad config and reports rejected config as false`() =
        runTest {
            val seen = mutableListOf<Cfg>()
            val bad = Context(JsonObject(mapOf("level" to JsonPrimitive("high"))))
            assertTrue(
                Platform {
                    seen += it
                    true
                }.initialize(bad)
            )
            assertEquals(listOf(Cfg()), seen)
            assertTrue(bad.warnings.single().startsWith("test.platform: invalid config, using defaults:"))

            val rejecting = Context()
            assertEquals(false, Platform { throw IllegalArgumentException("bad url") }.initialize(rejecting))
            assertEquals(listOf("test.platform: invalid config: bad url"), rejecting.warnings)

            val nullConfig = Context(JsonNull)
            assertTrue(Platform().initialize(nullConfig))
            assertEquals(emptyList(), nullConfig.warnings)
        }

    private class MultiRule : AbstractTypedMultiRuleModule<Cfg>(Cfg.serializer()) {
        override val id = "test.multi"
        override val name = "Multi"

        override suspend fun contributedRules() = setOf("a", "b")

        override fun doValidateTyped(
            ruleId: String,
            mr: MergeRequest,
            config: Cfg,
        ) = listOf(Finding(Severity.WARNING, "level ${config.level}", ruleId))
    }

    @Test
    fun `multi rule module validates only contributed rules and flags bad config`() =
        runTest {
            val module = MultiRule()
            val mr = MergeRequest(
                id = "1",
                iid = 1,
                title = "t",
                description = null,
                sourceBranch = "f",
                targetBranch = "main",
                author = User("dev", "Dev"),
                state = MergeRequestState.OPENED,
                draft = false,
                labels = emptyList(),
                commits = emptyList(),
                changes = emptyList(),
            )

            assertEquals(emptyList(), module.validate("c", mr, JsonObject(emptyMap())))
            assertEquals(listOf("level 3"), module.validate("a", mr, JsonObject(mapOf("level" to JsonPrimitive(3)))).map { it.message })
            val withBadConfig = module.validate("b", mr, JsonObject(mapOf("level" to JsonPrimitive("x"))))
            assertEquals(listOf(Severity.WARNING, Severity.INFO), withBadConfig.map { it.severity })
            assertTrue(withBadConfig.last().message.startsWith("Invalid config for rule 'b', using defaults:"))
            assertEquals(1, module.validate("a", mr, JsonNull).size)
            assertEquals(listOf("1.7.0", "10", "true"), listOf(module.version, "${module.defaultPriority}", "${module.defaultEnabled}"))
            assertTrue(module.initialize(Context()))
        }

    @Test
    fun `typed default config falls back to an empty object when a field is required`() =
        runTest {
            val module = object : AbstractTypedOutputModule<RequiredCfg>(RequiredCfg.serializer()) {
                override val id = "test.out"
                override val name = "Out"
                override val version = "0"
                override val outputId = "out"

                override suspend fun outputTyped(
                    report: FormattedReport,
                    config: RequiredCfg,
                ) = Unit
            }
            assertEquals(JsonObject(emptyMap()), module.defaultConfig())
            assertTrue(module.initialize(Context()))
        }

    @Test
    fun `typed output format and enriched reporter decode their config`() =
        runTest {
            val seen = mutableListOf<Cfg>()
            val output = object : AbstractTypedOutputModule<Cfg>(Cfg.serializer()) {
                override val id = "test.out"
                override val name = "Out"
                override val version = "0"
                override val outputId = "out"

                override suspend fun outputTyped(
                    report: FormattedReport,
                    config: Cfg,
                ) {
                    seen += config
                }
            }
            val format = object : AbstractTypedFormatModule<Cfg>(Cfg.serializer()) {
                override val id = "test.fmt"
                override val name = "Fmt"
                override val version = "0"
                override val formatId = "fmt"

                override suspend fun formatTyped(
                    report: ValidationReport,
                    config: Cfg,
                ) = FormattedReport("fmt", "level ${config.level}")
            }
            val reporter = object : AbstractTypedEnrichedReporterModule<Cfg>(Cfg.serializer()) {
                override val id = "test.rep"
                override val name = "Rep"
                override val version = "0"
                override val formatId = "rep"

                override suspend fun reportTyped(
                    findings: List<Finding>,
                    passed: Boolean,
                    config: Cfg,
                ): String? = null

                override suspend fun reportEnrichedTyped(
                    report: ValidationReport,
                    config: Cfg,
                ) = "enriched ${config.level}"
            }
            val config = JsonObject(mapOf("level" to JsonPrimitive(2)))

            output.output(FormattedReport("fmt", "b"), config)
            assertEquals(listOf(Cfg(2)), seen)
            assertEquals("level 2", format.format(report, config).body)
            assertEquals("level 1", format.format(report, JsonObject(mapOf("level" to JsonPrimitive("x")))).body)
            assertEquals("enriched 2", reporter.reportEnriched(report, config))
            assertEquals(JsonObject(emptyMap()), reporter.runtimeReporterConfig(config, config))
            assertTrue(format.initialize(Context()))
            assertEquals(JsonObject(mapOf("level" to JsonPrimitive(1))), format.defaultConfig())
        }

    @Test
    fun `untyped bases initialize without setup`() =
        runTest {
            val rule = object : AbstractRuleModule() {
                override val id = "r"
                override val name = "R"
                override val version = "0"

                override suspend fun contributedRules() = setOf("r")

                override suspend fun validate(
                    ruleId: String,
                    mr: MergeRequest,
                    configElement: JsonElement,
                ): List<Finding> = emptyList()
            }
            val reporter = object : AbstractReporterModule() {
                override val id = "rep"
                override val name = "Rep"
                override val version = "0"
                override val formatId = "rep"

                override suspend fun report(
                    findings: List<Finding>,
                    passed: Boolean,
                    config: JsonElement,
                ): String? = null
            }
            val output = object : AbstractOutputModule() {
                override val id = "o"
                override val name = "O"
                override val version = "0"
                override val outputId = "o"

                override suspend fun output(
                    report: FormattedReport,
                    config: JsonElement,
                ) = Unit
            }
            val format = object : AbstractFormatModule() {
                override val id = "f"
                override val name = "F"
                override val version = "0"
                override val formatId = "f"

                override suspend fun format(
                    report: ValidationReport,
                    config: JsonElement,
                ) = FormattedReport("f", "")
            }
            val profile = object : AbstractProfileModule() {
                override val profileId = "strict"
                override val name = "Strict"
                override val summary = "s"

                override suspend fun ruleConfigs(): Map<String, JsonElement> = emptyMap()
            }
            val context = Context()

            assertEquals(false, rule.defaultEnabled)
            assertEquals("io.karandash.profile.strict", profile.id)
            assertEquals("1.7.0", profile.version)
            listOf(rule, reporter, output, format, profile).forEach { assertTrue(it.initialize(context), it.id) }
        }

    @Test
    fun `config format adapter derives identity from the delegate`() =
        runTest {
            val yaml = object : ConfigFormatModule {
                override val formatId = "yaml"
                override val fileExtensions = setOf("yml", "yaml")

                override suspend fun parseToJsonElement(content: String): JsonElement = JsonPrimitive(content)
            }
            val bare = object : ConfigFormatModule {
                override val formatId = "toml"

                override suspend fun parseToJsonElement(content: String): JsonElement = JsonNull
            }
            val adapter = ConfigFormatModuleAdapter(yaml)

            assertEquals("io.karandash.config-format.yaml", adapter.id)
            assertEquals("Yaml Config Format Parser", adapter.name)
            assertEquals("Parses .yml config files.", adapter.description)
            assertEquals("Parses .toml config files.", ConfigFormatModuleAdapter(bare).description)
            assertEquals(ModuleType.CONFIG_FORMAT, adapter.type)
            assertEquals(JsonPrimitive("x"), adapter.parseToJsonElement("x"))
            assertTrue(adapter.initialize(Context()))
            adapter.shutdown()
        }

    @OptIn(InternalKarandashApi::class)
    @Test
    fun `scoped context factory falls back to the module id`() {
        val factory = object : ModuleScopedPluginContextFactory {
            override fun forModule(moduleId: String): PluginContext = Context(JsonPrimitive(moduleId))
        }

        assertEquals(JsonPrimitive("test.platform"), factory.forModule(Platform()).pluginConfig)
    }

    @Test
    fun `bridge host settings apply only a positive short sha length`() {
        val original = ShortSha.length
        try {
            listOf(JsonPrimitive(0), JsonPrimitive(-1), JsonPrimitive("x"), JsonObject(emptyMap())).forEach { value ->
                BridgeHostSettings.apply(JsonObject(mapOf(BridgeHostSettings.SHORT_SHA_LENGTH to value)))
                assertEquals(original, ShortSha.length, value.toString())
            }
            BridgeHostSettings.apply(JsonObject(emptyMap()))
            assertEquals(original, ShortSha.length)
            BridgeHostSettings.apply(JsonObject(mapOf(BridgeHostSettings.SHORT_SHA_LENGTH to JsonPrimitive(12))))
            assertEquals(12, ShortSha.length)
        } finally {
            ShortSha.configure(original)
        }
    }
}
