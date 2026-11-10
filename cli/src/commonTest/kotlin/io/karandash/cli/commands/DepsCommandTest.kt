package io.karandash.cli.commands

import com.github.ajalt.clikt.testing.test
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.ParsedMergeRequestUrl
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.TestFileHelpers
import io.karandash.cli.commands.dependencies.DepsCommand
import io.karandash.cli.errors.ExitCodes
import io.karandash.models.ChangeType
import io.karandash.models.DiffRefs
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** `deps` fetches the MR and each `--sibling` through the platform service and reports shared files. */
class DepsCommandTest {
    private val tempDir = TestFileHelpers.createTempDir("karandash-deps")
    private val config = Path(tempDir, "config.yaml").also { TestFileHelpers.writeFile(it, "rules: {}\n") }.toString()
    private val bootstrap = PluginBootstrap(envReader = { null })

    @AfterTest
    fun cleanup() = TestFileHelpers.deleteTempDir(tempDir)

    private suspend fun deps(vararg args: String) =
        bootstrap.loadPluginsFromDirs(emptyList()).let {
            bootstrap.registry().register(FakeScm)
            DepsCommand(bootstrap).test(listOf("--file", config) + args)
        }

    @Test
    fun `sibling touching the same file is reported as a conflict`() =
        runTest {
            val result = deps("--mr-url", "fakescm://repo/1", "--sibling", "fakescm://repo/2", "--sibling", "fakescm://repo/3")
            assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
            assertContains(result.stdout, "Sibling MRs: 2")
            assertContains(result.stdout, "- MR 2 ('mr 2'): src/Shared.kt")
        }

    @Test
    fun `analyze-cross-mr also compares siblings with each other`() =
        runTest {
            val siblings = arrayOf("--mr-url", "fakescm://repo/1", "--sibling", "fakescm://repo/3", "--sibling", "fakescm://repo/4")
            val pairwise = deps(*siblings, "--analyze-cross-mr")
            assertEquals(ExitCodes.SUCCESS, pairwise.statusCode, pairwise.output)
            assertContains(pairwise.stdout, "- MR 3 ↔ MR 4 ('mr 4'): src/Other.kt")

            val currentOnly = deps(*siblings)
            assertContains(currentOnly.stdout, "No conflicts or dependencies detected.")
        }

    @Test
    fun `no siblings reports nothing detected`() =
        runTest {
            val result = deps("--mr-url", "fakescm://repo/1")
            assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
            assertContains(result.stdout, "No conflicts or dependencies detected.")
        }

    @Test
    fun `unparseable url is a config error`() =
        runTest {
            val result = deps("--mr-url", "not-a-url")
            assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode, result.output)
            assertContains(result.stderr, "Dependency analysis failed: Cannot parse MR/PR URL: not-a-url")
        }

    @Test
    fun `mr-url is required`() {
        val result = DepsCommand(bootstrap).test(emptyList())
        assertEquals(1, result.statusCode, result.output)
        assertContains(result.stderr, "--mr-url")
    }

    private object FakeScm : PlatformServiceModule {
        override val id: String = "fakescm"
        override val name: String = "Fake SCM"
        override val version: String = "1.0.0"
        override val type: ModuleType = ModuleType.PLATFORM_SERVICE

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun parseMergeRequestUrl(url: String): ParsedMergeRequestUrl? =
            if (url.startsWith("fakescm://")) ParsedMergeRequestUrl(id, "fakescm://repo", "repo", url.substringAfterLast('/')) else null

        override suspend fun fetchMergeRequest(
            projectId: String,
            mrId: String,
        ): MergeRequest = mr(mrId)

        override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = mr(url.substringAfterLast('/'))

        /** MRs 1 and 2 both touch `src/Shared.kt`; MRs 3 and 4 both touch `src/Other.kt`. */
        private fun mr(iid: String): MergeRequest {
            val file = if (iid == "3" || iid == "4") "src/Other.kt" else "src/Shared.kt"
            return MergeRequest(
                id = iid,
                iid = iid.toInt(),
                title = "mr $iid",
                description = null,
                sourceBranch = "feature/$iid",
                targetBranch = "main",
                author = User(username = "dev", name = "Dev"),
                state = MergeRequestState.OPENED,
                draft = false,
                labels = emptyList(),
                commits = emptyList(),
                changes = listOf(FileChange(path = file, changeType = ChangeType.MODIFIED)),
            )
        }

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
