package io.karandash.cli.pipeline

import io.karandash.api.PluginContext
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.RuleModule
import io.karandash.api.platform.ParsedMergeRequestUrl
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.errors.ExitCodes
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FixSuggestion
import io.karandash.models.finding.Severity
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** `validate --fix` through the whole pipeline against a fake live provider (FixAndResultHandler.applyFixes). */
class FixFlowPipelineTest {
    private class FakeHub : PlatformServiceModule {
        val labels = mutableListOf<String>()
        override val id = "fixhub"
        override val name = "FixHub"
        override val version = "1.0"

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun addLabels(
            projectId: String,
            mrId: String,
            labels: List<String>,
        ) {
            this.labels += labels
        }

        override suspend fun removeLabels(
            projectId: String,
            mrId: String,
            labels: List<String>,
        ) {
            this.labels -= labels.toSet()
        }

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

        override suspend fun parseMergeRequestUrl(url: String): ParsedMergeRequestUrl? =
            if (url.startsWith(BASE)) ParsedMergeRequestUrl(platform = id, baseUrl = BASE, projectId = "o/r", mrId = "1") else null

        override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = mr()

        override suspend fun fetchMergeRequest(
            projectId: String,
            mrId: String,
        ): MergeRequest = mr()

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

        private fun mr() =
            MergeRequest(
                id = "1",
                iid = 1,
                title = "t",
                description = null,
                author = User(username = "a", name = "A"),
                sourceBranch = "f",
                targetBranch = "main",
                state = MergeRequestState.OPENED,
                draft = false,
                labels = labels.toList(),
                changes = emptyList(),
                commits = emptyList(),
                approvals = emptyList(),
                reviewers = emptyList(),
            )
    }

    /** Fails until the MR carries [LABEL], suggesting the label as a fix. */
    private object NeedsLabelRule : RuleModule {
        override val id = "fix-flow-rule-module"
        override val name = id
        override val version = "1.0"

        override suspend fun contributedRules(): Set<String> = setOf(RULE)

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ): List<Finding> =
            if (LABEL in mr.labels) {
                emptyList()
            } else {
                listOf(Finding(Severity.ERROR, "missing label", RULE, fixSuggestions = listOf(FixSuggestion.AddLabel(LABEL))))
            }
    }

    private class Run(
        val result: ValidationPipelineResult,
        val outputs: List<String>,
    )

    private suspend fun validateWithFix(
        hub: FakeHub,
        dryRun: Boolean,
    ): Run {
        val config = Path("cli/build/tmp/fix-flow.yaml")
        SystemFileSystem.createDirectories(checkNotNull(config.parent))
        val dryRunKeys = if (dryRun) "dry-run: true\ndry-run-scope: all\n" else ""
        SystemFileSystem.sink(config).buffered().use {
            it.writeString("${dryRunKeys}ai:\n  enabled: false\n  statistics:\n    dir: cli/build/tmp/stats\nrules:\n  $RULE: {}\n")
        }
        val bootstrap = PluginBootstrap(envReader = { null })
        bootstrap.loadPluginsFromDirs(emptyList())
        bootstrap.registry().register(NeedsLabelRule)
        bootstrap.registry().register(hub)
        val outputs = mutableListOf<String>()
        val options = ValidationOptions(
            configPathStr = config.toString(),
            mrDataStr = null,
            mrId = null,
            platform = null,
            project = null,
            prUrl = "$BASE/o/r/pull/1",
            format = "json",
            explicitFormat = true,
            outputPathStr = null,
            quiet = true,
            verbose = false,
            strict = false,
            dryRun = false,
            explain = false,
            fix = true,
            yes = true,
            noAutoDetect = true,
            outputWriter = { outputs += it },
            sharedBootstrap = bootstrap,
        )
        try {
            return Run(ValidationPipeline(options, bootstrap).execute(), outputs)
        } finally {
            bootstrap.shutdown()
        }
    }

    @Test
    fun `fix applies the suggested label and re-validates to success`() =
        runTest(timeout = 5.minutes) {
            val hub = FakeHub()
            val run = validateWithFix(hub, dryRun = false)

            assertEquals(listOf(LABEL), hub.labels)
            assertEquals(ExitCodes.SUCCESS, run.result.exitCode)
            assertTrue(run.result.validationResult?.passed == true)
            assertTrue(run.outputs.any { RULE !in it }, run.outputs.toString())
        }

    @Test
    fun `fix under a dry-run config plans the label but applies nothing and keeps the failure`() =
        runTest(timeout = 5.minutes) {
            val hub = FakeHub()
            val run = validateWithFix(hub, dryRun = true)

            assertTrue(hub.labels.isEmpty())
            assertEquals(ExitCodes.VALIDATION_FAILED, run.result.exitCode)
        }

    private companion object {
        const val BASE = "https://fixhub.test"
        const val RULE = "fix-flow-rule"
        const val LABEL = "needs-review"
    }
}
