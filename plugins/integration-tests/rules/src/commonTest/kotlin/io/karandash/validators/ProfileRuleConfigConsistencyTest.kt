package io.karandash.validators

import io.karandash.api.module.ProfileModule
import io.karandash.models.support.KarandashJson
import io.karandash.profiles.ConventionalCommitsProfileModule
import io.karandash.profiles.GitFlowProfileModule
import io.karandash.profiles.GitHubFlowProfileModule
import io.karandash.profiles.MinimalProfileModule
import io.karandash.profiles.SecurityConsciousProfileModule
import io.karandash.profiles.SemanticVersioningProfileModule
import io.karandash.profiles.TrunkBasedProfileModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * Verifies that every built-in profile emits rule config maps that can be deserialized
 * through the corresponding rule's [KSerializer] without data loss.
 *
 * Catches regressions where a profile emits keys that don't match any config field.
 */
class ProfileRuleConfigConsistencyTest {
    private val ruleConfigSerializers: Map<String, KSerializer<*>> = mapOf(
        TitleFormatRuleModule.RULE_ID to TitleFormatConfig.serializer(),
        CommitMessagesRuleModule.RULE_ID to CommitMessagesConfig.serializer(),
        CommitBodyRuleModule.RULE_ID to CommitBodyConfig.serializer(),
        SizeLimitsRuleModule.RULE_ID to SizeLimitsConfig.serializer(),
        DraftRuleModule.RULE_ID to DraftConfig.serializer(),
        BranchNamingRuleModule.RULE_ID to BranchNamingConfig.serializer(),
        SecurityRuleModule.RULE_ID to SecurityConfig.serializer(),
        FileChangesRuleModule.RULE_ID to FileChangesConfig.serializer(),
        LabelsRuleModule.RULE_ID to LabelsConfig.serializer(),
    )

    private val allProfiles: List<ProfileModule> = listOf(
        MinimalProfileModule(),
        ConventionalCommitsProfileModule(),
        GitHubFlowProfileModule(),
        GitFlowProfileModule(),
        TrunkBasedProfileModule(),
        SemanticVersioningProfileModule(),
        SecurityConsciousProfileModule(),
    )

    private fun <T> deserialize(
        serializer: KSerializer<T>,
        element: JsonElement
    ): T = KarandashJson.Default.decodeFromJsonElement(serializer, element)

    @Test
    fun allProfileRuleConfigsParseWithoutError() {
        runTest {
            val failures = mutableListOf<String>()

            for (profile in allProfiles) {
                for ((ruleId, configMap) in profile.ruleConfigs()) {
                    val serializer = ruleConfigSerializers[ruleId]
                    if (serializer == null) {
                        failures += "${profile.profileId}: unknown rule ID '$ruleId'"
                        continue
                    }
                    try {
                        val parsed = deserialize(serializer, configMap)
                        assertNotNull(parsed, "${profile.profileId}/$ruleId: deserialization returned null")
                    } catch (e: Exception) {
                        failures += "${profile.profileId}/$ruleId: ${e.message}"
                    }
                }
            }

            if (failures.isNotEmpty()) {
                fail("Profile rule config consistency failures:\n${failures.joinToString("\n")}")
            }
        }
    }

    @Test
    fun allProfileRuleConfigsRoundTripPreservesKeys() {
        runTest {
            for (profile in allProfiles) {
                for ((ruleId, configMap) in profile.ruleConfigs()) {
                    val serializer = ruleConfigSerializers[ruleId] ?: continue
                    val parsed = deserialize(serializer, configMap)
                    assertNotNull(parsed, "${profile.profileId}/$ruleId: deserialization returned null")
                }
            }
        }
    }

    @Test
    fun noProfileEmitsUnknownRuleIds() {
        runTest {
            for (profile in allProfiles) {
                for (ruleId in profile.ruleConfigs().keys) {
                    assertNotNull(
                        ruleConfigSerializers[ruleId],
                        "${profile.profileId} references unknown rule ID '$ruleId'. " +
                            "Add the corresponding KSerializer to ruleConfigSerializers map in this test.",
                    )
                }
            }
        }
    }
}
