package io.karandash.config.pipeline

import io.karandash.api.Module
import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.errors.ConfigException
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.ValidationReport
import io.karandash.api.module.CliCommandModule
import io.karandash.api.module.FormatModule
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.ReporterModule
import io.karandash.api.module.RuleModule
import io.karandash.api.support.mapToJsonElement
import io.karandash.manifest.ManifestData
import io.karandash.manifest.MissingPluginBehavior
import io.karandash.manifest.PluginDeclaration
import io.karandash.sdk.config.toAnyMap
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MissingModuleDetectorTest {
    /** The map under [key], with its keys as strings; fails the test when it is not a map. */
    private fun section(
        result: Map<String, Any?>,
        key: String,
    ): Map<String, Any?> =
        checkNotNull(result[key] as? Map<*, *>) { "$key is not a map: ${result[key]}" }.entries.associate { (k, v) -> k.toString() to v }

    private fun fakeRegistry(
        knownIds: Set<String>,
        ruleIds: Set<String> = knownIds,
        modules: List<Module> = emptyList(),
    ): ModuleRegistry =
        object : ModuleRegistry {
            override suspend fun register(module: Module) = Unit

            override suspend fun unregister(id: String): Boolean = false

            override fun get(id: String): Module? = null

            override fun getByType(type: ModuleType): List<Module> = modules.filter { it.type == type }

            override fun ruleModules(): List<RuleModule> = emptyList()

            override fun platformServices(): List<PlatformServiceModule> = emptyList()

            override fun reporters(): List<ReporterModule> = emptyList()

            override fun registeredIds(): Set<String> = knownIds

            override fun hasRule(ruleId: String): Boolean = ruleId in ruleIds

            override fun findRuleHandler(ruleId: String): RuleModule? = null

            override fun findByAlias(alias: String): PlatformServiceModule? = null

            override fun allAliases(): Map<String, PlatformServiceModule> = emptyMap()

            override fun aliasCollisions(): List<String> = emptyList()

            override fun cliCommandModules(): List<CliCommandModule> = emptyList()

            override fun aiProviders(): List<io.karandash.api.ai.AiProviderModule> = emptyList()

            override fun findAiProvider(providerId: String): io.karandash.api.ai.AiProviderModule? = null

            override fun aiInterceptors(): List<io.karandash.api.ai.AiInterceptorModule> = emptyList()
        }

    /** The detector reads [JsonElement]; these cases are written as plain maps. */
    private suspend fun MissingModuleDetector.check(raw: Map<String, Any?>): Map<String, Any?> =
        check(mapToJsonElement(raw) as JsonElement).toAnyMap()

    private fun detector(
        knownIds: Set<String>,
        behavior: MissingPluginBehavior,
        declarations: List<PluginDeclaration> = emptyList(),
        warnings: MutableList<String> = mutableListOf(),
    ): MissingModuleDetector =
        MissingModuleDetector(
            registry = fakeRegistry(knownIds),
            manifestData = ManifestData(pluginDeclarations = declarations),
            behavior = behavior,
            warn = { warnings.add(it) },
        )

    // ===== WARN mode =====

    @Test
    fun `WARN mode unknown rule key emits warning and strips from map`() {
        runTest {
            val warnings = mutableListOf<String>()
            val det = detector(
                knownIds = setOf("title-format"),
                behavior = MissingPluginBehavior.WARN,
                warnings = warnings,
            )
            val rawMap = mapOf<String, Any?>(
                "rules" to mapOf("title-format" to true, "unknown-rule" to true)
            )
            val result = det.check(rawMap)

            assertEquals(1, warnings.size)
            assertTrue(warnings[0].contains("unknown-rule"))

            val rulesSection = section(result, "rules")
            assertTrue(rulesSection.containsKey("title-format"))
            assertFalse(rulesSection.containsKey("unknown-rule"))
        }
    }

    @Test
    fun `WARN mode declared plugin emits E0302-style warning`() {
        runTest {
            val warnings = mutableListOf<String>()
            val det = detector(
                knownIds = setOf("title-format"),
                behavior = MissingPluginBehavior.WARN,
                declarations = listOf(PluginDeclaration(id = "com.example.my-rule")),
                warnings = warnings,
            )
            val rawMap = mapOf<String, Any?>(
                "rules" to mapOf("com.example.my-rule" to true)
            )
            det.check(rawMap)

            assertEquals(1, warnings.size)
            assertTrue(warnings[0].contains("E0302"))
            assertTrue(warnings[0].contains("com.example.my-rule"))
        }
    }

    @Test
    fun `WARN mode completely unknown rule key emits the E0002 unknown rule warning`() {
        runTest {
            val warnings = mutableListOf<String>()
            val det = detector(
                knownIds = setOf("title-format"),
                behavior = MissingPluginBehavior.WARN,
                warnings = warnings,
            )
            val rawMap = mapOf<String, Any?>(
                "rules" to mapOf("unknown-xyz" to true)
            )
            det.check(rawMap)

            assertEquals(1, warnings.size)
            assertTrue(warnings[0].startsWith("WARNING [E0002] Unknown plugin module: 'unknown-xyz' (rules)"), warnings[0])
        }
    }

    @Test
    fun `WARN mode unknown key with close match suggests correction`() {
        runTest {
            val warnings = mutableListOf<String>()
            val det = detector(
                knownIds = setOf("title-format"),
                behavior = MissingPluginBehavior.WARN,
                warnings = warnings,
            )
            val rawMap = mapOf<String, Any?>(
                "rules" to mapOf("title-formt" to true)
            )
            det.check(rawMap)

            assertEquals(1, warnings.size)
            assertTrue(warnings[0].contains("title-format"))
        }
    }

    // ===== FAIL mode =====

    @Test
    fun `FAIL mode single unknown rule throws ConfigException containing E0002`() {
        runTest {
            val det = detector(
                knownIds = setOf("title-format"),
                behavior = MissingPluginBehavior.FAIL,
            )
            val rawMap = mapOf<String, Any?>(
                "rules" to mapOf("unknown-rule" to true)
            )
            val ex = assertFailsWith<ConfigException> { det.check(rawMap) }
            val msg = checkNotNull(ex.message) { "exception has a message" }
            assertTrue(msg.startsWith("[E0002] "), msg)
            assertTrue(msg.contains("unknown-rule"))
        }
    }

    @Test
    fun `FAIL mode single declared-but-not-installed throws ConfigException containing E0302`() {
        runTest {
            val det = detector(
                knownIds = setOf("title-format"),
                behavior = MissingPluginBehavior.FAIL,
                declarations = listOf(PluginDeclaration(id = "com.example.my-rule")),
            )
            val rawMap = mapOf<String, Any?>(
                "rules" to mapOf("com.example.my-rule" to true)
            )
            val ex = assertFailsWith<ConfigException> { det.check(rawMap) }
            val msg = checkNotNull(ex.message) { "exception has a message" }
            assertTrue(msg.contains("E0302"))
            assertTrue(msg.contains("com.example.my-rule"))
        }
    }

    @Test
    fun `FAIL mode multiple unknowns throws ConfigException with batch message`() {
        runTest {
            val det = detector(
                knownIds = setOf("title-format"),
                behavior = MissingPluginBehavior.FAIL,
            )
            val rawMap = mapOf<String, Any?>(
                "rules" to mapOf("unknown-a" to true, "unknown-b" to true)
            )
            val ex = assertFailsWith<ConfigException> { det.check(rawMap) }
            val msg = checkNotNull(ex.message) { "exception has a message" }
            assertTrue(msg.contains("unknown-a"))
            assertTrue(msg.contains("unknown-b"))
            assertTrue(msg.contains("E0302/E0303"))
        }
    }

    // ===== IGNORE mode =====

    @Test
    fun `IGNORE mode strips unknowns silently with no warnings`() {
        runTest {
            val warnings = mutableListOf<String>()
            val det = detector(
                knownIds = setOf("title-format"),
                behavior = MissingPluginBehavior.IGNORE,
                warnings = warnings,
            )
            val rawMap = mapOf<String, Any?>(
                "rules" to mapOf("title-format" to true, "unknown-rule" to true)
            )
            val result = det.check(rawMap)

            assertTrue(warnings.isEmpty())

            val rulesSection = section(result, "rules")
            assertTrue(rulesSection.containsKey("title-format"))
            assertFalse(rulesSection.containsKey("unknown-rule"))
        }
    }

    @Test
    fun `IGNORE mode returns cleaned map`() {
        runTest {
            val det = detector(
                knownIds = setOf("console"),
                behavior = MissingPluginBehavior.IGNORE,
            )
            val rawMap = mapOf<String, Any?>(
                "reporters" to mapOf("console" to true, "ghost-reporter" to true)
            )
            val result = det.check(rawMap)

            val section = section(result, "reporters")
            assertEquals(setOf("console"), section.keys)
        }
    }

    /** The `rdjson` format as registered by the rdjson-format plugin. */
    private class RdjsonStub : FormatModule {
        override val formatId = "rdjson"
        override val id = "io.karandash.format.rdjson"
        override val name = "rdjson"
        override val version = "1.0"
        override val configKey = "rdjson"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun format(
            report: ValidationReport,
            config: JsonElement,
        ) = FormattedReport(formatId = formatId, body = "")
    }

    @Test
    fun `the reviewdog reporter key is known when the rdjson format is`() {
        runTest {
            val warnings = mutableListOf<String>()
            val det = MissingModuleDetector(
                registry = fakeRegistry(emptySet(), modules = listOf(RdjsonStub())),
                manifestData = ManifestData(),
                behavior = MissingPluginBehavior.WARN,
                warn = { warnings.add(it) },
            )
            val result = det.check(mapOf("reporters" to mapOf("reviewdog" to true)))

            assertEquals(emptyList(), warnings)

            assertEquals(setOf("reviewdog"), section(result, "reporters").keys)
        }
    }

    @Test
    fun `an unknown reporter key is warned and stripped`() {
        runTest {
            val warnings = mutableListOf<String>()
            val det = detector(
                knownIds = setOf("console"),
                behavior = MissingPluginBehavior.WARN,
                warnings = warnings,
            )
            val result = det.check(mapOf("reporters" to mapOf("console" to true, "no-such-reporter" to true)))

            assertTrue(warnings.single().contains("no-such-reporter"), warnings.toString())

            assertEquals(setOf("console"), section(result, "reporters").keys)
        }
    }

    // ===== Clean map (no unknowns) =====

    @Test
    fun `clean map with no unknowns returns map unchanged`() {
        runTest {
            val warnings = mutableListOf<String>()
            val det = detector(
                knownIds = setOf("title-format", "console"),
                behavior = MissingPluginBehavior.WARN,
                warnings = warnings,
            )
            val rawMap = mapOf<String, Any?>(
                "rules" to mapOf("title-format" to true),
                "reporters" to mapOf("console" to true),
            )
            val result = det.check(rawMap)

            assertTrue(warnings.isEmpty())
            assertEquals(rawMap, result)
        }
    }

    @Test
    fun `conditionals key in rules section is flagged as unknown`() {
        runTest {
            val warnings = mutableListOf<String>()
            val det = detector(
                knownIds = setOf("title-format"),
                behavior = MissingPluginBehavior.WARN,
                warnings = warnings,
            )
            val rawMap = mapOf<String, Any?>(
                "rules" to mapOf("title-format" to true, "conditionals" to listOf<Any>())
            )
            val result = det.check(rawMap)

            assertEquals(1, warnings.size)
            assertTrue(warnings[0].contains("conditionals"))

            val rulesSection = section(result, "rules")
            assertFalse(rulesSection.containsKey("conditionals"))
            assertTrue(rulesSection.containsKey("title-format"))
        }
    }

    // ===== Multiple sections =====

    @Test
    fun `WARN mode detects unknowns across multiple sections`() {
        runTest {
            val warnings = mutableListOf<String>()
            val det = detector(
                knownIds = setOf("title-format", "console"),
                behavior = MissingPluginBehavior.WARN,
                warnings = warnings,
            )
            val rawMap = mapOf<String, Any?>(
                "rules" to mapOf("title-format" to true, "bad-rule" to true),
                "reporters" to mapOf("console" to true, "bad-reporter" to true),
            )
            det.check(rawMap)

            assertEquals(2, warnings.size)
        }
    }

    // ===== providers section =====

    @Test
    fun `testUnknownProviderInWarnModeIsStripped`() {
        runTest {
            val warnings = mutableListOf<String>()
            val det = detector(
                knownIds = setOf("gitlab"),
                behavior = MissingPluginBehavior.WARN,
                warnings = warnings,
            )
            val rawMap = mapOf<String, Any?>(
                "platforms" to mapOf("gitlab" to true, "ghost-provider" to true)
            )
            val result = det.check(rawMap)

            assertEquals(1, warnings.size)
            assertTrue(warnings[0].contains("ghost-provider"))

            val section = section(result, "platforms")
            assertTrue(section.containsKey("gitlab"))
            assertFalse(section.containsKey("ghost-provider"))
        }
    }

    // ===== comment-posters section =====

    @Test
    fun `testUnknownCommentPosterInFailModeThrows`() {
        runTest {
            val det = detector(
                knownIds = setOf("gitlab"),
                behavior = MissingPluginBehavior.FAIL,
            )
            val rawMap = mapOf<String, Any?>(
                "platforms" to mapOf("gitlab" to true, "ghost-poster" to true)
            )
            val ex = assertFailsWith<ConfigException> { det.check(rawMap) }
            val msg = checkNotNull(ex.message) { "exception has a message" }
            assertTrue(msg.contains("E0302") || msg.contains("E0303"))
            assertTrue(msg.contains("ghost-poster"))
        }
    }

    // ===== mutators section =====

    @Test
    fun `testUnknownMutatorInIgnoreModeIsStripped`() {
        runTest {
            val warnings = mutableListOf<String>()
            val det = detector(
                knownIds = setOf("gitlab"),
                behavior = MissingPluginBehavior.IGNORE,
                warnings = warnings,
            )
            val rawMap = mapOf<String, Any?>(
                "platforms" to mapOf("gitlab" to true, "ghost-mutator" to true)
            )
            val result = det.check(rawMap)

            assertTrue(warnings.isEmpty())

            val section = section(result, "platforms")
            assertTrue(section.containsKey("gitlab"))
            assertFalse(section.containsKey("ghost-mutator"))
        }
    }

    @Test
    fun `merged camelCase keys match kebab-case module ids and declarations`() {
        runTest {
            val warnings = mutableListOf<String>()
            val det = detector(
                knownIds = setOf("title-length"),
                behavior = MissingPluginBehavior.WARN,
                declarations = listOf(PluginDeclaration(id = "acme-rule")),
                warnings = warnings,
            )
            val merged = buildJsonObject {
                putJsonObject("rules") {
                    putJsonObject("titleLength") {}
                    putJsonObject("acmeRule") {}
                }
            }
            val rules = (det.check(merged as JsonElement) as JsonObject).getValue("rules") as JsonObject

            assertEquals(setOf("titleLength"), rules.keys)
            assertEquals(1, warnings.size, warnings.toString())
            assertTrue("E0302" in warnings[0] && "'acme-rule'" in warnings[0], warnings[0])
        }
    }

    @Test
    fun `merged camelCase key matches a kebab-case contributed rule id`() {
        runTest {
            val warnings = mutableListOf<String>()
            val det = MissingModuleDetector(
                registry = fakeRegistry(knownIds = emptySet(), ruleIds = setOf("risk-scoring")),
                manifestData = ManifestData(),
                behavior = MissingPluginBehavior.WARN,
                warn = { warnings.add(it) },
            )
            val merged = buildJsonObject { putJsonObject("rules") { putJsonObject("riskScoring") {} } }
            val rules = (det.check(merged as JsonElement) as JsonObject).getValue("rules") as JsonObject

            assertEquals(setOf("riskScoring"), rules.keys)
            assertTrue(warnings.isEmpty(), warnings.toString())
        }
    }

    @Test
    fun `an AI analyzer id configured under rules is not reported as missing`() {
        runTest {
            val warnings = mutableListOf<String>()
            val analyzer = object : io.karandash.api.ai.AiAnalyzerModule {
                override val id = "ai-risk-scoring-analyzer"
                override val name = "Risk scoring"
                override val version = "1.0.0"
                override val analyzerId = "risk-scoring"

                override suspend fun initialize(context: io.karandash.api.PluginContext) = true

                override suspend fun analyze(context: io.karandash.api.ai.AiAnalyzerContext) =
                    emptyList<io.karandash.models.finding.Finding>()
            }
            val det = MissingModuleDetector(
                registry = fakeRegistry(knownIds = emptySet(), modules = listOf(analyzer)),
                manifestData = ManifestData(),
                behavior = MissingPluginBehavior.WARN,
                warn = { warnings.add(it) },
            )
            val merged = buildJsonObject { putJsonObject("rules") { putJsonObject("riskScoring") {} } }
            val rules = (det.check(merged as JsonElement) as JsonObject).getValue("rules") as JsonObject

            assertEquals(setOf("riskScoring"), rules.keys)
            assertTrue(warnings.isEmpty(), warnings.toString())
        }
    }
}
