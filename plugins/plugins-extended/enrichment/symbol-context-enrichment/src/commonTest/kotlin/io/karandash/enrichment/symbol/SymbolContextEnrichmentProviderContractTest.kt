package io.karandash.enrichment.symbol

import io.karandash.sdk.ModuleContractTest
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test

/** Module contract rules (docs/development/conventions.md) for [SymbolContextEnrichmentProvider]. */
class SymbolContextEnrichmentProviderContractTest : ModuleContractTest<SymbolContextEnrichmentProvider>() {
    override fun createModule() = SymbolContextEnrichmentProvider()

    // An enabled provider initializes only when an ast-grep binary is on PATH, so rule 1 is not asserted here
    // (SymbolContextEnrichmentProviderTest covers the enabled path); the disabled config keeps the suite hermetic.
    override fun validConfig(): JsonElement = obj("changed" to obj("enabled" to JsonPrimitive(false)))

    // The provider falls back to defaults, whose result depends on an ast-grep binary on PATH: rule 2 is checked as "never throws".
    override fun malformedConfig(): JsonElement? = null

    override suspend fun invokePrimaryWithEmptyInput(module: SymbolContextEnrichmentProvider) {
        module.enrich(createTestMergeRequest(title = "", sourceBranch = "", targetBranch = ""), JsonObject(emptyMap()))
    }

    @Test
    fun initializeWithMalformedConfigDoesNotThrow() =
        runTest {
            val module = createModule()
            module.initialize(testContext(obj("changed" to obj("enabled" to str("maybe")), "astGrepPath" to JsonArray(emptyList()))))
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
