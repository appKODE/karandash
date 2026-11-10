package io.karandash.sdk

import io.karandash.api.support.mapToJsonElement
import io.karandash.sdk.typed.AbstractTypedPlatformServiceModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypedPlatformServiceModuleTest {
    @Serializable
    data class StubPosterConfig(
        val prefix: String = "[karandash]",
        val dryRun: Boolean = false,
    )

    private class StubPoster : AbstractTypedPlatformServiceModule<StubPosterConfig>(StubPosterConfig.serializer()) {
        override val id = "io.karandash.test.stub-poster"
        override val name = "Stub Comment Poster"
        override val version = "0.0.1"

        var lastConfig: StubPosterConfig? = null
        var initResult: Boolean = true

        override suspend fun initializeTyped(
            context: PluginContext,
            config: StubPosterConfig
        ): Boolean {
            lastConfig = config
            return initResult
        }

        override suspend fun postComment(
            projectId: String,
            mrId: String,
            body: String
        ): String = "comment-1"

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
            diffRefs: io.karandash.models.DiffRefs,
            startLine: Int?,
        ): String = "inline-comment-1"
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
            val poster = StubPoster()
            val ctx = stubContext(mapToJsonElement(mapOf("prefix" to "[test]", "dryRun" to true)))
            assertTrue(poster.initialize(ctx))
            assertEquals(StubPosterConfig(prefix = "[test]", dryRun = true), poster.lastConfig)
        }

    @Test
    fun `initialize bridge uses defaults for empty pluginConfig`() =
        runTest {
            val poster = StubPoster()
            assertTrue(poster.initialize(stubContext()))
            assertEquals(StubPosterConfig(), poster.lastConfig)
        }

    @Test
    fun `initialize returns false when initializeTyped returns false`() =
        runTest {
            val poster = StubPoster()
            poster.initResult = false
            assertFalse(poster.initialize(stubContext()))
        }

    @Test
    fun `configSchema delegates to serializer schema`() {
        runTest {
            val poster = StubPoster()
            val schema = poster.configSchema()
            assertEquals(2, schema.size)
            assertEquals("prefix", schema[0].name)
            assertEquals("dryRun", schema[1].name)
        }
    }

    @Test
    fun `type returns PLATFORM_SERVICE`() {
        val poster = StubPoster()
        assertEquals(ModuleType.PLATFORM_SERVICE, poster.type)
    }
}
