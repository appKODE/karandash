package io.karandash.sdk

import io.karandash.api.support.mapToJsonElement
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.sdk.config.ConfigPropertyType
import io.karandash.sdk.typed.AbstractTypedSingleRuleModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypedRuleModuleTest {
    @Serializable
    data class StubConfig(
        val enabled: Boolean = true,
        val label: String = "default",
    )

    private class StubTypedRuleModule : AbstractTypedSingleRuleModule<StubConfig>(StubConfig.serializer()) {
        override val ruleId = "stub-rule"
        override val explanation = "Stub rule for testing"
        override val id = "io.karandash.test.stub"
        override val name = "Stub Rule"
        override val version = "0.0.1"
        override val description = "Stub rule module for testing TypedRuleModule interface"

        var lastConfig: StubConfig? = null

        override fun doValidateTyped(
            mr: MergeRequest,
            config: StubConfig
        ): List<Finding> {
            lastConfig = config
            return if (config.enabled) emptyList() else listOf(Finding(severity = Severity.ERROR, message = "disabled", rule = "stub-rule"))
        }
    }

    private fun stubMr() =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "test title",
            description = null,
            sourceBranch = "feature/test",
            targetBranch = "main",
            author = User(username = "tester", name = "Tester"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = emptyList(),
        )

    @Test
    fun `configSchema provides correct schema from serializer`() =
        runTest {
            val module = StubTypedRuleModule()
            val schema = module.configSchema()
            assertEquals(2, schema.size)
            assertEquals("enabled", schema[0].name)
            assertEquals(ConfigPropertyType.BOOLEAN, schema[0].type)
            assertEquals("label", schema[1].name)
            assertEquals(ConfigPropertyType.STRING, schema[1].type)
        }

    @Test
    fun `validate bridge delegates to validateTyped via configSpec fromJson`() =
        runTest {
            val module = StubTypedRuleModule()
            val findings = module.validate("stub-rule", stubMr(), mapToJsonElement(mapOf("enabled" to true)))
            assertTrue(findings.isEmpty())
            assertEquals(StubConfig(enabled = true, label = "default"), module.lastConfig)
        }

    @Test
    fun `validate bridge with disabled config produces finding`() =
        runTest {
            val module = StubTypedRuleModule()
            val findings = module.validate("stub-rule", stubMr(), mapToJsonElement(mapOf("enabled" to false)))
            assertEquals(1, findings.size)
            assertEquals("stub-rule", findings[0].rule)
        }

    @Test
    fun `configSchema returns schema from configSpec`() {
        runTest {
            val module = StubTypedRuleModule()
            val schema = module.configSchema()
            assertFalse(schema.isEmpty())
            assertEquals(2, schema.size)
        }
    }

    @Test
    fun `validate with empty map uses defaults from configSpec`() =
        runTest {
            val module = StubTypedRuleModule()
            module.validate("stub-rule", stubMr(), JsonObject(emptyMap()))
            assertEquals(StubConfig(enabled = true, label = "default"), module.lastConfig)
        }
}
