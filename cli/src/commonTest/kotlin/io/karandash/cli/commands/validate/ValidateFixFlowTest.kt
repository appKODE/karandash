package io.karandash.cli.commands.validate

import com.github.ajalt.clikt.core.ProgramResult
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.cli.errors.ExitCodes
import io.karandash.config.Config
import io.karandash.engine.ValidationEngine
import io.karandash.engine.ValidationResult
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FixSuggestion
import io.karandash.models.finding.Severity
import io.karandash.sdk.Logger
import io.karandash.sdk.typed.AbstractTypedPlatformServiceModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Drives [ValidateFixFlow] against a recording platform: dry-run, apply + re-validate, failures, CI guard. */
class ValidateFixFlowTest {
    private class RecordingPlatform(
        private val mr: MergeRequest,
        private val labelsFail: Boolean = false,
    ) : AbstractTypedPlatformServiceModule<JsonObject>(JsonObject.serializer()) {
        val calls = mutableListOf<String>()

        override val id = "fake"
        override val name = "Fake"
        override val version = "1.0.0"

        override suspend fun initializeTyped(
            context: PluginContext,
            config: JsonObject,
        ): Boolean = true

        override suspend fun fetchMergeRequest(
            projectId: String,
            mrId: String,
        ): MergeRequest {
            calls += "fetch $projectId $mrId"
            return mr
        }

        override suspend fun addLabels(
            projectId: String,
            mrId: String,
            labels: List<String>,
        ) {
            calls += "addLabels $labels"
            if (labelsFail) error("label API down")
        }

        override suspend fun updateTitle(
            projectId: String,
            mrId: String,
            title: String,
        ) {
            calls += "updateTitle $title"
        }

        override suspend fun postComment(
            projectId: String,
            mrId: String,
            body: String,
        ): String = ""

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
        ): String = ""
    }

    private class RecordingLogger : Logger {
        val infos = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) {
            infos += message
        }

        override fun warn(message: String) = Unit

        override fun error(message: String) = Unit
    }

    private val mr = MergeRequest(
        id = "1",
        iid = 7,
        title = "wip",
        description = null,
        author = User(username = "dev", name = "Dev"),
        sourceBranch = "feature",
        targetBranch = "main",
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        changes = emptyList(),
        commits = emptyList(),
    )

    private val fixable = ValidationResult(
        listOf(
            Finding(
                rule = "labels",
                message = "missing label",
                severity = Severity.ERROR,
                fixSuggestions = listOf(FixSuggestion.AddLabel("bug")),
            ),
            Finding(
                rule = "titleFormat",
                message = "bad title",
                severity = Severity.WARNING,
                fixSuggestions = listOf(FixSuggestion.SetTitle("feat: real title")),
            ),
        ),
    )

    private val logger = RecordingLogger()

    private fun flow(
        yes: Boolean = true,
        dryRun: Boolean = false,
        isCi: Boolean = false,
    ) = ValidateFixFlow(
        yes = yes,
        dryRun = dryRun,
        effectivePlatform = null,
        effectiveProject = "group/repo",
        effectiveMrId = "7",
        isCiEnvironment = { isCi },
        logger = logger,
        runtimeModuleInitializer = {},
    )

    private suspend fun ValidateFixFlow.apply(
        result: ValidationResult,
        platform: RecordingPlatform,
    ): FixFlowResult {
        val config = Config()
        val registry = ModuleRegistryImpl()
        return applyAutoFixes(
            result = result,
            provider = platform,
            engine = ValidationEngine(config, registry),
            pairs = emptyList(),
            planner = FormatOutputPlanner(
                format = null,
                explicitFormat = false,
                outputTarget = null,
                hasPlatformContext = false,
                logger = logger,
            ),
            registry = registry,
        )
    }

    @Test
    fun `dry run reports the planned fixes and applies none`() =
        runTest {
            val platform = RecordingPlatform(mr)

            val outcome = flow(dryRun = true).apply(fixable, platform)

            assertEquals(emptyList(), platform.calls, "dry run must not mutate or re-fetch the MR")
            assertSame(fixable, outcome.validationResult)
            assertTrue(logger.infos.any { it.contains("Add label 'bug'") }, "planned fix listed: ${logger.infos}")
            assertTrue(logger.infos.any { it.startsWith("dry-run: 2 fix(es) planned") }, "${logger.infos}")
        }

    @Test
    fun `confirmed fixes are applied then the MR is re-fetched and re-validated`() =
        runTest {
            val platform = RecordingPlatform(mr)

            val outcome = flow().apply(fixable, platform)

            assertEquals(
                setOf("addLabels [bug]", "updateTitle feat: real title", "fetch group/repo 7"),
                platform.calls.toSet(),
            )
            assertEquals("fetch group/repo 7", platform.calls.last(), "re-fetch happens after the mutations")
            assertEquals(emptyList(), outcome.validationResult.findings, "re-validation result replaces the original")
        }

    @Test
    fun `no fix suggestions means no mutation and the original result`() =
        runTest {
            val platform = RecordingPlatform(mr)
            val plain = ValidationResult(listOf(Finding(rule = "r", message = "m", severity = Severity.ERROR)))

            val outcome = flow().apply(plain, platform)

            assertEquals(emptyList(), platform.calls)
            assertSame(plain, outcome.validationResult)
        }

    @Test
    fun `a failed fix exits with validation failure and skips re-validation`() =
        runTest {
            val platform = RecordingPlatform(mr, labelsFail = true)

            val error = assertFailsWith<ProgramResult> { flow().apply(fixable, platform) }

            assertEquals(ExitCodes.VALIDATION_FAILED, error.statusCode)
            assertTrue(platform.calls.none { it.startsWith("fetch") }, "${platform.calls}")
        }

    @Test
    fun `without --yes in CI the flow refuses before mutating`() =
        runTest {
            val platform = RecordingPlatform(mr)

            val error = assertFailsWith<ProgramResult> { flow(yes = false, isCi = true).apply(fixable, platform) }

            assertEquals(ExitCodes.CONFIG_ERROR, error.statusCode)
            assertEquals(emptyList(), platform.calls)
        }
}
