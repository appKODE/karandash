package io.karandash.enrichment.gitlabissue

import io.karandash.sdk.ModuleContractTest
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test

/** Module contract rules (docs/development/conventions.md) for [GitLabIssueEnrichmentProvider]. */
class GitLabIssueEnrichmentProviderContractTest : ModuleContractTest<GitLabIssueEnrichmentProvider>() {
    override fun createModule() = GitLabIssueEnrichmentProvider()

    override fun validConfig(): JsonElement = obj("project-id" to JsonPrimitive(123), "token" to str("t0ken"))

    override fun malformedConfig(): JsonElement = obj("project-id" to obj("id" to JsonPrimitive(123)), "token" to str("t0ken"))

    override suspend fun invokePrimaryWithEmptyInput(module: GitLabIssueEnrichmentProvider) {
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
