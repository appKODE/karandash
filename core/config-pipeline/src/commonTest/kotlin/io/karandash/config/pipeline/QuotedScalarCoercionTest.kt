package io.karandash.config.pipeline

import io.karandash.api.InternalKarandashApi
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.sdk.typed.TypedRuleModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** a literal quoted number in a typed rule's config is coerced with a warning; interpolated values are not literals. */
@OptIn(InternalKarandashApi::class)
class QuotedScalarCoercionTest {
    @Serializable
    data class QuotedConfig(
        val minApprovals: Int = 1,
        val label: String = "",
    )

    private class QuotedRule : TypedRuleModule<QuotedConfig> {
        override val configSerializer = QuotedConfig.serializer()
        override val id = "quoted-rule"
        override val name = "Quoted Rule"
        override val version = "1.0"

        override suspend fun contributedRules() = setOf("quoted-rule")

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun validateTyped(
            ruleId: String,
            mr: MergeRequest,
            config: QuotedConfig,
        ) = emptyList<Finding>()
    }

    private suspend fun warningsFor(rule: kotlinx.serialization.json.JsonObject): List<String> {
        val registry = ModuleRegistryImpl().apply { register(QuotedRule()) }
        return QuotedScalarCoercion.warnings(buildJsonObject { put("rules", buildJsonObject { put("quoted-rule", rule) }) }, registry)
    }

    @Test
    fun `literal quoted number warns and string field does not`() =
        runTest {
            val warnings = warningsFor(
                buildJsonObject {
                    put("min-approvals", "2")
                    put("label", "007")
                }
            )
            assertEquals(1, warnings.size)
            assertTrue("rules.quotedRule.minApprovals" in warnings.single(), warnings.single())
        }

    @Test
    fun `interpolated value does not warn`() =
        runTest {
            assertEquals(emptyList(), warningsFor(buildJsonObject { put("min-approvals", "\${MIN}") }))
        }
}
