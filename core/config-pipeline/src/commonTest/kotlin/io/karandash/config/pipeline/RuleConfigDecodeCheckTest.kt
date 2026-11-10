package io.karandash.config.pipeline

import io.karandash.api.InternalKarandashApi
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.config.RulesConfig
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.typed.TypedRuleModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** a rule config that cannot be decoded is reported at load instead of silently running on defaults. */
@OptIn(InternalKarandashApi::class)
class RuleConfigDecodeCheckTest {
    @Serializable
    enum class Delimiter {
        @SerialName("brackets")
        BRACKETS,

        @SerialName("parens")
        PARENS,
    }

    @Serializable
    data class ApprovalLikeConfig(
        val minApprovals: Int = 1,
        val delimiter: Delimiter = Delimiter.PARENS,
        val maxTokens: Long = 100,
    ) {
        init {
            require(minApprovals >= 0) { "minApprovals must not be negative" }
        }
    }

    private class ApprovalLikeRule : TypedRuleModule<ApprovalLikeConfig> {
        override val configSerializer = ApprovalLikeConfig.serializer()
        override val id = "approval-like"
        override val name = "Approval Like"
        override val version = "1.0"

        override suspend fun contributedRules() = setOf("approval-like")

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun validateTyped(
            ruleId: String,
            mr: MergeRequest,
            config: ApprovalLikeConfig,
        ) = emptyList<Finding>()
    }

    private suspend fun errorsFor(config: JsonElement): List<String> {
        val registry = ModuleRegistryImpl().apply { register(ApprovalLikeRule()) }
        return RuleConfigDecodeCheck.errors(RulesConfig(ruleConfigs = mapOf("approvalLike" to config)), registry)
    }

    /** `minApprovals:"2"` parses under the lenient and the rule-config preset. */
    @Test
    fun `quoted number parses under lenient decoding`() =
        runTest {
            val config = buildJsonObject { put("minApprovals", "2") }

            val decoded = KarandashJson.Default.decodeFromJsonElement(ApprovalLikeConfig.serializer(), config)

            assertEquals(2, decoded.minApprovals)
            assertEquals(emptyList(), errorsFor(config))
        }

    /** Lowercase enum values and quoted numbers from `${VAR}` interpolation stay valid. */
    @Test
    fun `lowercase enum and quoted long stay valid`() =
        runTest {
            val config = buildJsonObject {
                put("delimiter", "brackets")
                put("maxTokens", "4096")
            }

            assertEquals(emptyList(), errorsFor(config))
        }

    /** A value of the wrong type is reported with the rule id, not swallowed. */
    @Test
    fun `wrong value type is reported`() =
        runTest {
            val errors = errorsFor(buildJsonObject { put("minApprovals", "many") })

            assertEquals(1, errors.size, errors.toString())
            assertTrue("approvalLike" in errors.single(), errors.single())
        }

    /** An unknown enum value is reported instead of being coerced to the default. */
    @Test
    fun `unknown enum value is reported`() =
        runTest {
            val errors = errorsFor(buildJsonObject { put("delimiter", "braces") })

            assertEquals(1, errors.size, errors.toString())
        }

    /** A value that breaks the config class's own `require` is reported. */
    @Test
    fun `require violation is reported`() =
        runTest {
            val errors = errorsFor(buildJsonObject { put("minApprovals", -1) })

            assertEquals(1, errors.size, errors.toString())
            assertTrue("must not be negative" in errors.single(), errors.single())
        }

    /** Unknown keys are the warn-only concern of [UnknownNestedKeys]; a scalar config is the schema coercer's. */
    @Test
    fun `unknown keys and scalar configs are not decode errors`() =
        runTest {
            assertEquals(emptyList(), errorsFor(buildJsonObject { put("minApproval", 3) }))
            assertEquals(emptyList(), errorsFor(JsonPrimitive(42)))
        }
}
