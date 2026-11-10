package io.karandash.validators

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.module.RuleModule
import io.karandash.plugins.ai.AiRuleModule
import io.karandash.sdk.ModuleContractTest
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test

/**
 * Module contract rules (docs/development/conventions.md) for every built-in rule module in
 * [ModuleRegistrationCompletenessTest.ALL_RULE_MODULE_FACTORIES].
 *
 * A rule module receives its config per [RuleModule.validate] call, not at initialize, so rule 2 ("malformed
 * config fails without throwing") is checked at validate: a malformed rule config must yield findings or an
 * empty list, never an exception.
 */
class RuleModuleContractTest {
    private class Contract(
        private val factory: () -> RuleModule,
        private val malformed: JsonElement? = null,
    ) : ModuleContractTest<RuleModule>() {
        override fun createModule() = factory()

        override fun malformedConfig(): JsonElement? = malformed

        override suspend fun invokePrimaryWithEmptyInput(module: RuleModule) {
            val emptyMr = createTestMergeRequest(title = "", sourceBranch = "", targetBranch = "")
            module.contributedRules().forEach { ruleId ->
                module.validate(ruleId, emptyMr, JsonObject(emptyMap()))
            }
        }
    }

    private val contracts = ModuleRegistrationCompletenessTest.ALL_RULE_MODULE_FACTORIES.map { Contract(it) }

    /** The AI rule module is the one rule module configured at initialize (provider, model, features). */
    private val aiContract = Contract(
        factory = { AiRuleModule(ModuleRegistryImpl()) },
        malformed = JsonObject(mapOf("provider" to NESTED, "features" to JsonPrimitive("all"))),
    )

    // AiRuleModule returns false without a registered AI provider and API key (none here); its valid-config
    // initialize is covered by plugins-ai tests (AiCostSummaryTest and others).
    @Test
    fun `initialize returns true for valid config`() =
        runTest { contracts.filterNot { it.createModule() is AiRuleModule }.forEach { it.verifyInitializeWithValidConfig() } }

    @Test
    fun `ai rule module initialize returns false for malformed config and never throws`() =
        runTest { aiContract.verifyInitializeWithMalformedConfig() }

    @Test
    fun `shutdown without initialize does not throw`() = runTest { contracts.forEach { it.verifyShutdownWithoutInitialize() } }

    @Test
    fun `shutdown is idempotent`() = runTest { contracts.forEach { it.verifyShutdownIsIdempotent() } }

    @Test
    fun `validate with empty merge request and empty config does not throw`() =
        runTest { contracts.forEach { it.verifyPrimaryMethodWithEmptyInput() } }

    @Test
    fun `validate with malformed config does not throw`() =
        runTest {
            ModuleRegistrationCompletenessTest.ALL_RULE_MODULE_FACTORIES.forEach { factory ->
                val module = factory()
                module.initialize(Contract(factory).testContext())
                module.contributedRules().forEach { ruleId ->
                    MALFORMED_CONFIGS.forEach { config -> module.validate(ruleId, createTestMergeRequest(), config) }
                }
                module.shutdown()
            }
        }

    private companion object {
        val NESTED = JsonObject(mapOf("unexpected" to JsonPrimitive(1)))

        /** Keys the built-in rules read, each given a value of the wrong JSON type. */
        private val COMMON_KEYS = listOf(
            "enabled",
            "severity",
            "pattern",
            "patterns",
            "regex",
            "required",
            "allowed",
            "forbidden",
            "min",
            "max",
            "minLength",
            "maxLength",
            "maxFiles",
            "maxLines",
            "maxAdditions",
            "labels",
            "rules",
            "paths",
            "level",
            "message",
            "minApprovals",
            "branches",
            "prefixes",
            "keywords",
            "sections",
            "languages",
            "dependencies",
        )

        val MALFORMED_CONFIGS: List<JsonElement> = listOf(
            JsonPrimitive("not-a-valid-config"),
            JsonArray(listOf(JsonPrimitive(1))),
            JsonObject(COMMON_KEYS.associateWith { NESTED }),
            JsonObject(COMMON_KEYS.associateWith { JsonPrimitive(-1) }),
            JsonObject(COMMON_KEYS.associateWith { JsonArray(listOf(NESTED)) }),
        )
    }
}
