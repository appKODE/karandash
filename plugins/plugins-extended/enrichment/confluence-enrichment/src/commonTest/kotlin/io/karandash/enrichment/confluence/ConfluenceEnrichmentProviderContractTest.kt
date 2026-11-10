package io.karandash.enrichment.confluence

import io.karandash.sdk.ModuleContractTest
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test

/** Module contract rules (docs/development/conventions.md) for [ConfluenceEnrichmentProvider]. */
class ConfluenceEnrichmentProviderContractTest : ModuleContractTest<ConfluenceEnrichmentProvider>() {
    override fun createModule() = ConfluenceEnrichmentProvider()

    override fun validConfig(): JsonElement = obj("url" to str("https://wiki.example.com"), "token" to str("t0ken"))

    override fun malformedConfig(): JsonElement = obj("url" to obj("host" to str("wiki.example.com")), "token" to str("t0ken"))

    override suspend fun invokePrimaryWithEmptyInput(module: ConfluenceEnrichmentProvider) {
        module.enrich(createTestMergeRequest(title = "", sourceBranch = "", targetBranch = ""), JsonObject(emptyMap()))
    }

    @Test
    fun initializeWithValidConfig() = runTest { verifyInitializeWithValidConfig() }

    @Test
    fun initializeWithMalformedConfig() = runTest { verifyInitializeWithMalformedConfig() }

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
