package io.karandash.cli.commands

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.RateLimitInfo
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.doctor.DoctorCheckResult
import io.karandash.cli.commands.doctor.DoctorCommandCommon
import io.karandash.config.Config
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DoctorAuthCheckTest {
    private val configResult = DoctorCheckResult.Pass(
        "ok",
        config = Config(platforms = mapOf("fake" to JsonObject(emptyMap()))),
    )
    private val bootstrap = PluginBootstrap()

    private suspend fun check(service: PlatformServiceModule): List<DoctorCheckResult> =
        DoctorCommandCommon.checkAuth(configResult, listOf(service)) { id, config ->
            bootstrap.contextWithConfig(id, config)
        }

    @Test
    fun `rejected credentials fail the auth check`() =
        runTest {
            val service = FakePlatformService(auth = false)
            val result = check(service).single()
            assertIs<DoctorCheckResult.Fail>(result)
            assertTrue(result.message.contains("credentials rejected"), result.message)
            assertEquals(1, service.authCalls)
            assertTrue(service.shutDown)
        }

    @Test
    fun `rejected credentials put the token location in the hint`() =
        runTest {
            val result = check(FakePlatformService(auth = false, tokenHint = "Set FAKE_TOKEN.")).single()
            assertIs<DoctorCheckResult.Fail>(result)
            assertEquals("fake: credentials rejected (HTTP 401/403)", result.message)
            assertEquals("Set FAKE_TOKEN.", result.hint)
        }

    @Test
    fun `failed initialization and a missing service hint at the platforms block`() =
        runTest {
            val init = check(FakePlatformService(auth = true, initializes = false)).single()
            assertIs<DoctorCheckResult.Fail>(init)
            assertEquals("fake: platform service failed to initialize", init.message)
            assertEquals("Check the 'platforms.fake' block: its URL and token settings.", init.hint)

            val missing = DoctorCommandCommon
                .checkAuth(configResult, emptyList()) { id, config ->
                    bootstrap.contextWithConfig(id, config)
                }.single()
            assertIs<DoctorCheckResult.Fail>(missing)
            assertEquals("Remove 'platforms.fake' or install the plugin that provides it.", missing.hint)
        }

    @Test
    fun `accepted credentials pass the auth check`() =
        runTest {
            val result = check(FakePlatformService(auth = true)).single()
            assertIs<DoctorCheckResult.Pass>(result)
            assertTrue(result.message.contains("credentials accepted"), result.message)
        }

    @Test
    fun `accepted credentials show the remaining API rate limit`() =
        runTest {
            val result = check(FakePlatformService(auth = true, rateLimit = RateLimitInfo(4990, 5000, 0))).single()
            assertTrue(result.message.contains("API rate limit: 4990/5000 remaining"), result.message)
        }

    @Test
    fun `platform without a service fails`() =
        runTest {
            val result = DoctorCommandCommon
                .checkAuth(configResult, emptyList()) { id, config ->
                    bootstrap.contextWithConfig(id, config)
                }.single()
            assertIs<DoctorCheckResult.Fail>(result)
        }

    private class FakePlatformService(
        private val auth: Boolean?,
        private val rateLimit: RateLimitInfo? = null,
        private val tokenHint: String? = null,
        private val initializes: Boolean = true,
    ) : PlatformServiceModule {
        val authCalls get() = calls.size
        val shutDown get() = shutdowns.isNotEmpty()
        private val calls = mutableListOf<Unit>()
        private val shutdowns = mutableListOf<Unit>()

        override val id: String = "fake"
        override val name: String = "Fake"
        override val version: String = "1.0.0"
        override val type: ModuleType = ModuleType.PLATFORM_SERVICE

        override suspend fun initialize(context: PluginContext): Boolean = initializes

        override fun tokenLocationHint(): String? = tokenHint

        override suspend fun shutdown() {
            shutdowns += Unit
        }

        override suspend fun rateLimitStatus(): RateLimitInfo? = rateLimit

        override suspend fun checkAuth(): Boolean? {
            calls += Unit
            return auth
        }

        override suspend fun fetchMergeRequest(
            projectId: String,
            mrId: String,
        ): MergeRequest = error("not used")

        override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = error("not used")

        override suspend fun addLabels(
            projectId: String,
            mrId: String,
            labels: List<String>,
        ) = Unit

        override suspend fun removeLabels(
            projectId: String,
            mrId: String,
            labels: List<String>,
        ) = Unit

        override suspend fun setDraft(
            projectId: String,
            mrId: String,
            draft: Boolean,
        ) = Unit

        override suspend fun updateTitle(
            projectId: String,
            mrId: String,
            title: String,
        ) = Unit

        override suspend fun updateDescription(
            projectId: String,
            mrId: String,
            description: String,
        ) = Unit

        override suspend fun approve(
            projectId: String,
            mrId: String,
        ) = Unit

        override suspend fun unapprove(
            projectId: String,
            mrId: String,
        ) = Unit

        override suspend fun postComment(
            projectId: String,
            mrId: String,
            body: String,
        ) = ""

        override suspend fun updateComment(
            projectId: String,
            mrId: String,
            commentId: String,
            body: String,
        ) = Unit

        override suspend fun findExistingComment(
            projectId: String,
            mrId: String,
            marker: String,
        ): String? = null

        override suspend fun postInlineComment(
            projectId: String,
            mrId: String,
            body: String,
            filePath: String,
            line: Int,
            diffRefs: DiffRefs,
            startLine: Int?,
        ) = ""
    }
}
