package io.karandash.cli.pipeline

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.platform.MergeOptions
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.validate.ValidateCommandScope
import io.karandash.config.AutoApproveConfig
import io.karandash.config.Config
import io.karandash.engine.ValidationEngine
import io.karandash.engine.ValidationResult
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.sdk.NoOpLogger
import io.karandash.sdk.typed.AbstractTypedPlatformServiceModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** Drives [AutoApproveHandler] end to end against a recording platform service. */
class AutoApproveHandlerTest {
    private class RecordingPlatform(
        private val approveFails: Boolean = false,
        private val mergeFails: Boolean = false,
    ) : AbstractTypedPlatformServiceModule<JsonObject>(JsonObject.serializer()) {
        val calls = mutableListOf<String>()

        override val id = "fake"
        override val name = "Fake"
        override val version = "1.0.0"

        override suspend fun initializeTyped(
            context: PluginContext,
            config: JsonObject,
        ): Boolean = true

        override suspend fun approve(
            projectId: String,
            mrId: String,
        ) {
            calls += "approve $projectId $mrId"
            if (approveFails) error("approve rejected")
        }

        override suspend fun unapprove(
            projectId: String,
            mrId: String,
        ) {
            calls += "unapprove $projectId $mrId"
        }

        override suspend fun mergeMr(
            projectId: String,
            mrId: String,
            options: MergeOptions,
        ): Result<Unit> {
            calls += "merge $projectId $mrId"
            return if (mergeFails) Result.failure(IllegalStateException("conflict")) else Result.success(Unit)
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

    private suspend fun run(
        platform: RecordingPlatform,
        autoApprove: AutoApproveConfig?,
        findings: List<Finding> = emptyList(),
        flag: Boolean = false,
        dryRun: Boolean = false,
    ): List<String> {
        val config = Config(autoApprove = autoApprove)
        val registry = ModuleRegistryImpl()
        val mr = MergeRequest(
            id = "1",
            iid = 7,
            title = "t",
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
        AutoApproveHandler(
            logger = NoOpLogger,
            autoApproveFlag = flag,
            dryRun = dryRun,
            runtimeModuleInitializer = { _, _, _ -> },
            resolveCIContext = { CIContext(platform = null, project = null, mrId = null, isCI = false) },
            resolveModuleConfig = { _, _, _ -> JsonObject(emptyMap()) },
        ).applyAutoApprove(
            configStage = ConfigLoadStage(config, null, false, registry, PluginBootstrap()),
            scope = ValidateCommandScope(config, NoOpLogger, existingRegistry = registry),
            providerStage = ProviderStage(provider = platform, mr = mr, projectId = "group/repo", mrId = "7"),
            validationStage = ValidationStage(ValidationEngine(config, registry), ValidationResult(findings)),
        )
        return platform.calls
    }

    private fun finding(severity: Severity) = Finding(rule = "r", message = "m", severity = severity)

    @Test
    fun `clean MR is approved and not merged by default`() =
        runTest {
            assertEquals(listOf("approve group/repo 7"), run(RecordingPlatform(), AutoApproveConfig()))
        }

    @Test
    fun `merge true merges after the approval`() =
        runTest {
            val calls = run(RecordingPlatform(), AutoApproveConfig(merge = true))
            assertEquals(listOf("approve group/repo 7", "merge group/repo 7"), calls)
        }

    @Test
    fun `a failed approval does not merge`() =
        runTest {
            val calls = run(RecordingPlatform(approveFails = true), AutoApproveConfig(merge = true))
            assertEquals(listOf("approve group/repo 7"), calls)
        }

    @Test
    fun `a failed merge is logged and does not throw`() =
        runTest {
            val calls = run(RecordingPlatform(mergeFails = true), AutoApproveConfig(merge = true))
            assertEquals(listOf("approve group/repo 7", "merge group/repo 7"), calls)
        }

    @Test
    fun `findings above the threshold unapprove and never merge`() =
        runTest {
            val calls = run(RecordingPlatform(), AutoApproveConfig(merge = true), listOf(finding(Severity.WARNING)))
            assertEquals(listOf("unapprove group/repo 7"), calls)
        }

    @Test
    fun `maxSeverity warning still approves a warning`() =
        runTest {
            val config = AutoApproveConfig(maxSeverity = "warning")
            assertEquals(listOf("approve group/repo 7"), run(RecordingPlatform(), config, listOf(finding(Severity.WARNING))))
            assertEquals(
                listOf("unapprove group/repo 7"),
                run(RecordingPlatform(), AutoApproveConfig(), listOf(finding(Severity.WARNING))),
            )
        }

    @Test
    fun `disabled config without the flag and dry run do nothing`() =
        runTest {
            assertEquals(emptyList(), run(RecordingPlatform(), null))
            assertEquals(emptyList(), run(RecordingPlatform(), AutoApproveConfig(enabled = false)))
            assertEquals(listOf("approve group/repo 7"), run(RecordingPlatform(), AutoApproveConfig(enabled = true)))
            assertEquals(emptyList(), run(RecordingPlatform(), AutoApproveConfig(merge = true), dryRun = true))
        }

    @Test
    fun `the flag enables auto-approve without config`() =
        runTest {
            assertEquals(listOf("approve group/repo 7"), run(RecordingPlatform(), null, flag = true))
        }
}
