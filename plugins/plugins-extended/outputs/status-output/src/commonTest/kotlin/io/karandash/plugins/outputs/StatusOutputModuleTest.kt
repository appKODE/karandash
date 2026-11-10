package io.karandash.plugins.outputs

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.finding.FormattedReport
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.PlatformOperation
import io.karandash.api.platform.ResultAnnotationLevel
import io.karandash.api.platform.ResultStatus
import io.karandash.api.platform.ResultStatusPublisher
import io.karandash.helpers.output.AiRunStatus
import io.karandash.helpers.output.NotificationState
import io.karandash.helpers.output.OnError
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.sdk.ModuleContractTest
import io.karandash.testfixtures.TestPluginContext
import io.karandash.translations.russian.RussianTranslationModule
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatusOutputModuleTest : ModuleContractTest<StatusOutputModule>() {
    override fun createModule() = StatusOutputModule()

    override fun malformedConfig() = null

    @Test
    fun shutdownWithoutInitialize() = runTest { verifyShutdownWithoutInitialize() }

    @Test
    fun shutdownIsIdempotent() = runTest { verifyShutdownIsIdempotent() }

    @Test
    fun initializeWithValidConfig() = runTest { verifyInitializeWithValidConfig() }

    @Test
    fun shutdownAfterInitialize() = runTest { verifyShutdownAfterInitialize() }

    private val body =
        """
        {"message":"Null check missing","location":{"path":"src/A.kt","range":{"start":{"line":3},"end":{"line":5}}},"severity":"ERROR","code":{"value":"null-check"}}
        {"message":"Long method","location":{"path":"src/B.kt","range":{"start":{"line":9}}},"severity":"WARNING","code":{"value":"long-method"}}
        {"message":"Title too short","severity":"INFO","code":{"value":"title"}}
        """.trimIndent()

    @Test
    fun `publishes the verdict with one annotation per diagnostic that has a line`() =
        runTest {
            val platform = FakePlatform(publishes = true)
            output(platform, StatusOutputConfig(detailsUrl = "https://ci.example.com/jobs/1"))

            val status = checkNotNull(platform.published)
            assertEquals("https://ci.example.com/jobs/1", status.detailsUrl)
            assertEquals("bbb", status.headSha)
            assertEquals(false, status.passed)
            assertEquals("karandash", status.name)
            assertEquals(listOf("src/A.kt", "src/B.kt"), status.annotations.map { it.path })
            assertEquals(listOf(ResultAnnotationLevel.FAILURE, ResultAnnotationLevel.WARNING), status.annotations.map { it.level })
            assertEquals(5, status.annotations.first().endLine)
            assertEquals("null-check", status.annotations.first().title)
        }

    @Test
    fun `the title and summary follow the report language`() =
        runTest {
            RussianTranslationModule.register()
            val english = FakePlatform(publishes = true)
            output(english, StatusOutputConfig())
            assertEquals("Failed: 1 error(s), 1 warning(s)", english.published?.title)
            assertEquals("karandash found 3 issue(s): 1 error(s), 1 warning(s).", english.published?.summary)

            val russian = FakePlatform(publishes = true)
            output(russian, StatusOutputConfig(language = "ru"))
            assertEquals("Не пройдено: 1 ошибка, 1 предупреждение", russian.published?.title)
            assertEquals("karandash нашёл 3 замечания: 1 ошибка, 1 предупреждение.", russian.published?.summary)
        }

    @Test
    fun `the pipeline verdict wins over the error count`() =
        runTest {
            val platform = FakePlatform(publishes = true)
            output(platform, StatusOutputConfig(name = "lint", notificationState = NotificationState(passed = true, findings = "x")))

            assertEquals(true, platform.published?.passed)
            assertEquals("lint", platform.published?.name)
        }

    @Test
    fun `an AI outage is published as its own status next to the verdict`() =
        runTest {
            val platform = FakePlatform(publishes = true)
            output(platform, StatusOutputConfig(aiStatus = AiRunStatus("AI: unavailable — 12/12 calls failed", passed = false)))

            assertEquals(listOf("karandash", "karandash/ai"), platform.all.map { it.name })
            val ai = platform.all.last()
            assertEquals(false, ai.passed)
            assertEquals("AI: unavailable — 12/12 calls failed", ai.title)
        }

    @Test
    fun `without AI configured only the verdict is published`() =
        runTest {
            val platform = FakePlatform(publishes = true)
            output(platform, StatusOutputConfig())

            assertEquals(listOf("karandash"), platform.all.map { it.name })
        }

    @Test
    fun `a platform without a result status skips with a warning`() =
        runTest {
            val platform = FakePlatform(publishes = false)
            output(platform, StatusOutputConfig())

            assertNull(platform.published)
        }

    @Test
    fun `a failed publish warns by default and fails the output under onError fail`() =
        runTest {
            output(FakePlatform(publishes = true, fails = true), StatusOutputConfig())
            assertFailsWith<IllegalStateException> {
                output(FakePlatform(publishes = true, fails = true), StatusOutputConfig(onError = OnError.FAIL))
            }
        }

    @Test
    fun `without a platform context nothing is published`() =
        runTest {
            val module = StatusOutputModule()
            assertTrue(module.initialize(context()))
            module.outputTyped(FormattedReport(formatId = "rdjsonl", body = body), StatusOutputConfig())
        }

    private suspend fun output(
        platform: FakePlatform,
        config: StatusOutputConfig,
    ) {
        val module = StatusOutputModule()
        module.initialize(context())
        module.configurePlatformContext(platform, "team/app", "7", DiffRefs(baseSha = "aaa", headSha = "bbb"), emptyMap())
        module.outputTyped(FormattedReport(formatId = "rdjsonl", body = body), config)
    }

    private fun context() = TestPluginContext()
}

/** A platform that records the published status; [publishes] decides whether it declares RESULT_STATUS. */
private class FakePlatform(
    private val publishes: Boolean,
    private val fails: Boolean = false,
) : PlatformServiceModule,
    ResultStatusPublisher {
    override val id: String = "fake"
    override val name: String = "Fake"
    override val version: String = "1"
    override val type: ModuleType = ModuleType.PLATFORM_SERVICE
    var published: ResultStatus? = null
    val all = mutableListOf<ResultStatus>()

    override suspend fun supports(operation: PlatformOperation): Boolean = publishes && operation == PlatformOperation.RESULT_STATUS

    override suspend fun publishResultStatus(
        projectId: String,
        mrId: String,
        status: ResultStatus,
    ) {
        check(!fails) { "HTTP 403" }
        if (published == null) published = status
        all += status
    }

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun fetchMergeRequest(
        projectId: String,
        mrId: String,
    ): MergeRequest = error("unused")

    override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = error("unused")

    override suspend fun addLabels(
        projectId: String,
        mrId: String,
        labels: List<String>,
    ) {}

    override suspend fun removeLabels(
        projectId: String,
        mrId: String,
        labels: List<String>,
    ) {}

    override suspend fun setDraft(
        projectId: String,
        mrId: String,
        draft: Boolean,
    ) {}

    override suspend fun updateTitle(
        projectId: String,
        mrId: String,
        title: String,
    ) {}

    override suspend fun updateDescription(
        projectId: String,
        mrId: String,
        description: String,
    ) {}

    override suspend fun approve(
        projectId: String,
        mrId: String,
    ) {}

    override suspend fun unapprove(
        projectId: String,
        mrId: String,
    ) {}

    override suspend fun postComment(
        projectId: String,
        mrId: String,
        body: String,
    ): String = error("unused")

    override suspend fun updateComment(
        projectId: String,
        mrId: String,
        commentId: String,
        body: String,
    ) {}

    override suspend fun findExistingComment(
        projectId: String,
        mrId: String,
        marker: String,
    ): String? = null

    override suspend fun findAllMatchingComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): List<String> = emptyList()

    override suspend fun postInlineComment(
        projectId: String,
        mrId: String,
        body: String,
        filePath: String,
        line: Int,
        diffRefs: DiffRefs,
        startLine: Int?,
    ): String = error("unused")
}
