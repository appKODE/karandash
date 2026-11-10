package io.karandash.sdk

import io.karandash.api.support.mapToJsonElement
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.sdk.typed.AbstractTypedPlatformServiceModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypedProviderModuleTest {
    @Serializable
    data class StubProviderConfig(
        val apiUrl: String = "https://api.example.com",
        val timeout: Int = 30,
    )

    private class StubProvider : AbstractTypedPlatformServiceModule<StubProviderConfig>(StubProviderConfig.serializer()) {
        override val id = "io.karandash.test.stub-provider"
        override val name = "Stub Provider"
        override val version = "0.0.1"

        var lastConfig: StubProviderConfig? = null
        var initResult: Boolean = true

        override suspend fun initializeTyped(
            context: PluginContext,
            config: StubProviderConfig
        ): Boolean {
            lastConfig = config
            return initResult
        }

        override suspend fun fetchMergeRequest(
            projectId: String,
            mrId: String
        ): MergeRequest =
            MergeRequest(
                id = mrId,
                iid = mrId.toIntOrNull() ?: 0,
                title = "stub",
                description = null,
                sourceBranch = "feature",
                targetBranch = "main",
                author = User(username = "u", name = "U"),
                state = MergeRequestState.OPENED,
                draft = false,
                labels = emptyList(),
                commits = emptyList(),
                changes = emptyList(),
            )

        override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = fetchMergeRequest("stub", "1")

        override suspend fun addLabels(
            projectId: String,
            mrId: String,
            labels: List<String>
        ) {}

        override suspend fun removeLabels(
            projectId: String,
            mrId: String,
            labels: List<String>
        ) {}

        override suspend fun setDraft(
            projectId: String,
            mrId: String,
            draft: Boolean
        ) {}

        override suspend fun updateTitle(
            projectId: String,
            mrId: String,
            title: String
        ) {}

        override suspend fun updateDescription(
            projectId: String,
            mrId: String,
            description: String
        ) {}

        override suspend fun approve(
            projectId: String,
            mrId: String
        ) {}

        override suspend fun unapprove(
            projectId: String,
            mrId: String
        ) {}

        override suspend fun postComment(
            projectId: String,
            mrId: String,
            body: String
        ): String = ""

        override suspend fun updateComment(
            projectId: String,
            mrId: String,
            commentId: String,
            body: String
        ) {}

        override suspend fun findExistingComment(
            projectId: String,
            mrId: String,
            marker: String
        ): String? = null

        override suspend fun postInlineComment(
            projectId: String,
            mrId: String,
            body: String,
            filePath: String,
            line: Int,
            diffRefs: DiffRefs,
            startLine: Int?,
        ): String = ""
    }

    private fun stubContext(config: JsonObject = JsonObject(emptyMap())) =
        object : PluginContext {
            override val pluginConfig: JsonElement = config
            override val hostVersion: String = "0.0.0"
            override val workingDirectory: String = "."

            override fun env(name: String): String? = null

            override fun log(message: String) {}

            override fun warn(message: String) {}
        }

    @Test
    fun `initialize bridge delegates to initializeTyped with deserialized config`() =
        runTest {
            val provider = StubProvider()
            val ctx = stubContext(mapToJsonElement(mapOf("apiUrl" to "https://custom.api", "timeout" to 60)))
            assertTrue(provider.initialize(ctx))
            assertEquals(StubProviderConfig(apiUrl = "https://custom.api", timeout = 60), provider.lastConfig)
        }

    @Test
    fun `initialize bridge uses defaults for empty pluginConfig`() =
        runTest {
            val provider = StubProvider()
            assertTrue(provider.initialize(stubContext()))
            assertEquals(StubProviderConfig(), provider.lastConfig)
        }

    @Test
    fun `initialize returns false when initializeTyped returns false`() =
        runTest {
            val provider = StubProvider()
            provider.initResult = false
            assertFalse(provider.initialize(stubContext()))
        }

    @Test
    fun `configSchema delegates to serializer schema`() {
        runTest {
            val provider = StubProvider()
            val schema = provider.configSchema()
            assertEquals(2, schema.size)
            assertEquals("apiUrl", schema[0].name)
            assertEquals("timeout", schema[1].name)
        }
    }

    @Test
    fun `type returns PLATFORM_SERVICE`() {
        val provider = StubProvider()
        assertEquals(ModuleType.PLATFORM_SERVICE, provider.type)
    }
}
