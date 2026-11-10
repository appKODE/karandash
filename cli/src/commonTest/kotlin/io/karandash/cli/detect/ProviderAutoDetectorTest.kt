package io.karandash.cli.detect

import io.karandash.api.PluginContext
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.CIEnvironment
import io.karandash.api.platform.ProviderDetectionScore
import io.karandash.detect.ProviderAutoDetector
import io.karandash.models.MergeRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderAutoDetectorTest {
    private fun makeProvider(
        moduleId: String,
        score: Double?,
        source: String = "ci:$moduleId",
    ): PlatformServiceModule =
        object : PlatformServiceModule {
            override val id = moduleId
            override val name = moduleId
            override val version = "1.0"

            override suspend fun initialize(context: PluginContext) = true

            override suspend fun detectConfidence(env: (String) -> String?,): ProviderDetectionScore? =
                score?.let {
                    ProviderDetectionScore(
                        providerId = moduleId,
                        score = it,
                        source = source,
                        ciEnvironment = CIEnvironment(
                            platform = moduleId,
                            projectId = "test/repo",
                            mrId = "1",
                            token = null,
                            baseUrl = null,
                        ),
                    )
                }

            override suspend fun fetchMergeRequest(
                projectId: String,
                mrId: String,
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
                startLine: Int?,
            ) = ""

            override suspend fun shutdown() {}
        }

    @Test
    fun `detectBest returns highest scoring provider`() =
        runTest {
            val detector = ProviderAutoDetector(
                providers = listOf(
                    makeProvider("gitlab", 0.9),
                    makeProvider("github", 0.5),
                    makeProvider("bitbucket", 0.7),
                ),
                envProvider = { null },
            )

            val best = detector.detectBest()
            assertNotNull(best)
            assertEquals("gitlab", best.providerId)
            assertEquals(0.9, best.score)
        }

    @Test
    fun `detectBest returns null when no provider meets threshold`() =
        runTest {
            val detector = ProviderAutoDetector(
                providers = listOf(
                    makeProvider("gitlab", 0.1),
                    makeProvider("github", 0.2),
                ),
                envProvider = { null },
            )

            assertNull(detector.detectBest())
        }

    @Test
    fun `detectBest returns null when no providers detect anything`() =
        runTest {
            val detector = ProviderAutoDetector(
                providers = listOf(
                    makeProvider("gitlab", null),
                    makeProvider("github", null),
                ),
                envProvider = { null },
            )

            assertNull(detector.detectBest())
        }

    @Test
    fun `detectAll returns sorted scores descending`() =
        runTest {
            val detector = ProviderAutoDetector(
                providers = listOf(
                    makeProvider("github", 0.5),
                    makeProvider("gitlab", 0.9),
                    makeProvider("bitbucket", 0.7),
                ),
                envProvider = { null },
            )

            val all = detector.detectAll()
            assertEquals(3, all.size)
            assertEquals("gitlab", all[0].providerId)
            assertEquals("bitbucket", all[1].providerId)
            assertEquals("github", all[2].providerId)
        }

    @Test
    fun `detectAll returns empty list when nothing detected`() =
        runTest {
            val detector = ProviderAutoDetector(
                providers = listOf(
                    makeProvider("gitlab", null),
                ),
                envProvider = { null },
            )

            assertTrue(detector.detectAll().isEmpty())
        }
}
