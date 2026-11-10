package io.karandash.sdk

import io.karandash.api.support.mapToJsonElement
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.sdk.typed.AbstractTypedPlatformServiceModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypedMutatorModuleTest {
    @Serializable
    data class StubMutatorConfig(
        val autoLabel: Boolean = true,
        val labelPrefix: String = "mr-",
    )

    private class StubMutator : AbstractTypedPlatformServiceModule<StubMutatorConfig>(StubMutatorConfig.serializer()) {
        override val id = "io.karandash.test.stub-mutator"
        override val name = "Stub Mutator"
        override val version = "0.0.1"

        var lastConfig: StubMutatorConfig? = null
        var initResult: Boolean = true

        override suspend fun initializeTyped(
            context: PluginContext,
            config: StubMutatorConfig
        ): Boolean {
            lastConfig = config
            return initResult
        }

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

        override suspend fun fetchMergeRequest(
            projectId: String,
            mrId: String
        ): MergeRequest = error("not implemented in test stub")

        override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = error("not implemented in test stub")

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
            val mutator = StubMutator()
            val ctx = stubContext(mapToJsonElement(mapOf("autoLabel" to false, "labelPrefix" to "custom-")))
            assertTrue(mutator.initialize(ctx))
            assertEquals(StubMutatorConfig(autoLabel = false, labelPrefix = "custom-"), mutator.lastConfig)
        }

    @Test
    fun `initialize bridge uses defaults for empty pluginConfig`() =
        runTest {
            val mutator = StubMutator()
            assertTrue(mutator.initialize(stubContext()))
            assertEquals(StubMutatorConfig(), mutator.lastConfig)
        }

    @Test
    fun `initialize returns false when initializeTyped returns false`() =
        runTest {
            val mutator = StubMutator()
            mutator.initResult = false
            assertFalse(mutator.initialize(stubContext()))
        }

    @Test
    fun `configSchema delegates to serializer schema`() {
        runTest {
            val mutator = StubMutator()
            val schema = mutator.configSchema()
            assertEquals(2, schema.size)
            assertEquals("autoLabel", schema[0].name)
            assertEquals("labelPrefix", schema[1].name)
        }
    }

    @Test
    fun `type returns PLATFORM_SERVICE`() {
        val mutator = StubMutator()
        assertEquals(ModuleType.PLATFORM_SERVICE, mutator.type)
    }
}
