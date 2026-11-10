package io.karandash.api

import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.support.mapToJsonElement
import io.karandash.models.MergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModuleDefaultsTest {
    @Test
    fun `Module defaultPriority returns 100 by default`() {
        val module = object : Module {
            override val id = "test"
            override val name = "Test"
            override val version = "1.0"
            override val type get() = ModuleType.RULE

            override suspend fun initialize(context: PluginContext) = true
        }
        assertEquals(100, module.defaultPriority)
    }

    @Test
    fun `Module defaultConfig returns empty map by default`() {
        runTest {
            val module = object : Module {
                override val id = "test"
                override val name = "Test"
                override val version = "1.0"
                override val type get() = ModuleType.RULE

                override suspend fun initialize(context: PluginContext) = true
            }
            assertTrue((module.defaultConfig() as JsonObject).isEmpty())
        }
    }

    @Test
    fun `Module defaultEnabled returns true by default`() {
        val module = object : Module {
            override val id = "test"
            override val name = "Test"
            override val version = "1.0"
            override val type get() = ModuleType.RULE

            override suspend fun initialize(context: PluginContext) = true
        }
        assertTrue(module.defaultEnabled)
    }

    @Test
    fun `PlatformServiceModule messages returns empty list by default`() {
        runTest {
            val module = object : PlatformServiceModule {
                override val id = "test"
                override val name = "Test"
                override val version = "1.0"

                override suspend fun initialize(context: PluginContext) = true

                override suspend fun fetchMergeRequest(
                    projectId: String,
                    mrId: String
                ): MergeRequest = error("stub")

                override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = error("stub")

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
                ) = ""

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
                    startLine: Int?
                ) = ""

                override suspend fun shutdown() {}
            }
            assertTrue(module.messages().isEmpty())
        }
    }

    @Test
    fun `Module can override defaultPriority`() {
        val module = object : Module {
            override val id = "test"
            override val name = "Test"
            override val version = "1.0"
            override val type get() = ModuleType.RULE
            override val defaultPriority = 42

            override suspend fun initialize(context: PluginContext) = true
        }
        assertEquals(42, module.defaultPriority)
    }

    @Test
    fun `Module can override defaultConfig`() {
        runTest {
            val module = object : Module {
                override val id = "test"
                override val name = "Test"
                override val version = "1.0"
                override val type get() = ModuleType.RULE

                override suspend fun initialize(context: PluginContext) = true

                override suspend fun defaultConfig() = mapToJsonElement(mapOf("key" to "value"))
            }
            assertEquals(mapToJsonElement(mapOf("key" to "value")), module.defaultConfig())
        }
    }

    @Test
    fun `PlatformServiceModule defaultApiUrl returns null by default`() {
        runTest {
            val module = object : PlatformServiceModule {
                override val id = "test"
                override val name = "Test"
                override val version = "1.0"

                override suspend fun initialize(context: PluginContext) = true

                override suspend fun fetchMergeRequest(
                    projectId: String,
                    mrId: String
                ): MergeRequest = error("stub")

                override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = error("stub")

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
                ) = ""

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
                    startLine: Int?
                ) = ""

                override suspend fun shutdown() {}
            }
            assertNull(module.defaultApiUrl())
        }
    }

    @Test
    fun `PlatformServiceModule dslName defaults to id`() {
        val module = object : PlatformServiceModule {
            override val id = "my-provider"
            override val name = "Test"
            override val version = "1.0"

            override suspend fun initialize(context: PluginContext) = true

            override suspend fun fetchMergeRequest(
                projectId: String,
                mrId: String
            ): MergeRequest = error("stub")

            override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = error("stub")

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
            ) = ""

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
                startLine: Int?
            ) = ""

            override suspend fun shutdown() {}
        }
        assertEquals("my-provider", module.dslName)
    }
}
