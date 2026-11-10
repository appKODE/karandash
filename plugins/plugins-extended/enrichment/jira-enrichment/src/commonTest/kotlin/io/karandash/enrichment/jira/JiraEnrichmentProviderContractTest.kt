package io.karandash.enrichment.jira

import io.karandash.sdk.ModuleContractTest
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test

/** Module contract rules (docs/development/conventions.md) for [JiraEnrichmentProvider]. */
class JiraEnrichmentProviderContractTest : ModuleContractTest<JiraEnrichmentProvider>() {
    override fun createModule() = JiraEnrichmentProvider()

    override fun validConfig(): JsonElement = obj()

    // The provider falls back to defaults (key extraction without the API), so a malformed config still initializes: rule 2 is checked as "never throws".
    override fun malformedConfig(): JsonElement? = null

    override suspend fun invokePrimaryWithEmptyInput(module: JiraEnrichmentProvider) {
        module.enrich(createTestMergeRequest(title = "", sourceBranch = "", targetBranch = ""), JsonObject(emptyMap()))
    }

    @Test
    fun initializeWithValidConfig() = runTest { verifyInitializeWithValidConfig() }

    @Test
    fun initializeWithMalformedConfigDoesNotThrow() =
        runTest {
            val module = createModule()
            module.initialize(testContext(obj("url" to obj("host" to str("jira.example.com")), "token" to JsonArray(listOf(str("t0ken"))))))
            module.shutdown()
        }

    @Test
    fun shutdownWithoutInitialize() = runTest { verifyShutdownWithoutInitialize() }

    @Test
    fun shutdownIsIdempotent() = runTest { verifyShutdownIsIdempotent() }

    @Test
    fun enrichWithEmptyInput() = runTest { verifyPrimaryMethodWithEmptyInput() }

    private companion object {
        fun obj(vararg entries: Pair<String, JsonElement>) = JsonObject(mapOf(*entries))

        fun str(value: String) = JsonPrimitive(value)
    }
}
