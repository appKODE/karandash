package io.karandash.sdk

import io.karandash.api.support.mapToJsonElement
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.sdk.typed.AbstractTypedSingleRuleModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AbstractTypedSingleRuleModuleTest {
    @Serializable
    data class StubConfig(
        val maxLength: Int = 50,
        val required: Boolean = false,
    )

    private class StubModule : AbstractTypedSingleRuleModule<StubConfig>(StubConfig.serializer()) {
        override val ruleId = "stub"
        override val explanation = "Stub rule explanation"
        override val id = "io.karandash.test.stub"
        override val name = "Stub Module"
        override val version = "0.0.1"
        override val description = "Stub for AbstractTypedSingleRuleModule tests"

        val capturedConfigs = mutableListOf<StubConfig>()

        override fun doValidateTyped(
            mr: MergeRequest,
            config: StubConfig
        ): List<Finding> {
            capturedConfigs.add(config)
            return if (mr.title.length > config.maxLength) {
                listOf(Finding(severity = Severity.ERROR, message = "Title too long", rule = ruleId))
            } else {
                emptyList()
            }
        }
    }

    private fun stubMr(title: String = "short title") =
        MergeRequest(
            id = "1",
            iid = 1,
            title = title,
            description = null,
            sourceBranch = "feature/x",
            targetBranch = "main",
            author = User(username = "u", name = "User"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = emptyList(),
        )

    @Test
    fun `contributedRules returns set containing ruleId`() {
        runTest {
            val module = StubModule()
            assertEquals(setOf("stub"), module.contributedRules())
        }
    }

    @Test
    fun `ruleExplanation returns explanation for matching ruleId`() {
        runTest {
            val module = StubModule()
            assertEquals("Stub rule explanation", module.ruleExplanation("stub"))
        }
    }

    @Test
    fun `ruleExplanation returns null for unknown ruleId`() {
        runTest {
            val module = StubModule()
            assertNull(module.ruleExplanation("other-rule"))
        }
    }

    @Test
    fun `initialize with PluginContext returns true`() =
        runTest {
            val module = StubModule()
            val context = object : PluginContext {
                override val pluginConfig: JsonElement = JsonObject(emptyMap())
                override val hostVersion: String = "0.0.0"
                override val workingDirectory: String = "."

                override fun env(name: String): String? = null

                override fun log(message: String) {}

                override fun warn(message: String) {}
            }
            assertTrue(module.initialize(context))
        }

    @Test
    fun `initialize with empty map uses defaults from configSpec`() =
        runTest {
            val module = StubModule()
            module.validate("stub", stubMr(), JsonObject(emptyMap()))
            assertEquals(1, module.capturedConfigs.size)
            assertEquals(StubConfig(maxLength = 50, required = false), module.capturedConfigs[0])
        }

    @Test
    fun `initialize with valid map coerces values correctly`() =
        runTest {
            val module = StubModule()
            module.validate("stub", stubMr(), mapToJsonElement(mapOf("maxLength" to 10, "required" to true)))
            assertEquals(StubConfig(maxLength = 10, required = true), module.capturedConfigs[0])
        }

    @Test
    fun `doValidateTyped receives typed config object`() =
        runTest {
            val module = StubModule()
            val config = mapToJsonElement(mapOf("maxLength" to 5))
            module.validate("stub", stubMr(title = "short"), config)
            assertEquals(StubConfig(maxLength = 5, required = false), module.capturedConfigs[0])
        }

    @Test
    fun `validation passes when title within maxLength`() =
        runTest {
            val module = StubModule()
            val findings = module.validate("stub", stubMr(title = "ok"), mapToJsonElement(mapOf("maxLength" to 10)))
            assertTrue(findings.isEmpty())
        }

    @Test
    fun `validation fails when title exceeds maxLength`() =
        runTest {
            val module = StubModule()
            val findings = module.validate("stub", stubMr(title = "this title is too long"), mapToJsonElement(mapOf("maxLength" to 5)))
            assertEquals(1, findings.size)
            assertEquals("stub", findings[0].rule)
            assertEquals(Severity.ERROR, findings[0].severity)
        }

    @Test
    fun `validate returns empty list for wrong ruleId`() =
        runTest {
            val module = StubModule()
            val findings = module.validate("other-rule", stubMr(title = "x".repeat(100)), mapToJsonElement(mapOf("maxLength" to 1)))
            assertTrue(findings.isEmpty())
            assertTrue(module.capturedConfigs.isEmpty())
        }

    @Test
    fun `configSchema returns schema from configSpec`() {
        runTest {
            val module = StubModule()
            val schema = module.configSchema()
            assertEquals(2, schema.size)
            assertEquals("maxLength", schema[0].name)
            assertEquals("required", schema[1].name)
        }
    }

    @Test
    fun `hover documentation is generated from the config class`() {
        val hover = checkNotNull(StubModule().hoverDocumentation)
        assertTrue(hover.startsWith("# stub"), hover)
        assertTrue("- `maxLength` (int) - default: `50`" in hover, hover)
    }
}
