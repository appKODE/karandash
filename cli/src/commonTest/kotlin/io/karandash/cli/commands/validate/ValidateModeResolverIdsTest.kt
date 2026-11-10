package io.karandash.cli.commands.validate

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.ParsedMergeRequestUrl
import io.karandash.cli.provider.ProviderResolver
import io.karandash.cli.provider.ProviderRuntime
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.sdk.NoOpLogger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `--pr` mode must carry the project/MR ids parsed from the URL so `--fix` and auto-approve mutate the
 * right MR (they used `("", "")` before).
 */
class ValidateModeResolverIdsTest {
    internal object FakeHub : PlatformServiceModule {
        override val id: String = "fakehub"
        override val name: String = "FakeHub"
        override val version: String = "1.0"

        override suspend fun initialize(context: PluginContext): Boolean = true

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

        override suspend fun parseMergeRequestUrl(url: String): ParsedMergeRequestUrl? =
            if (url.startsWith("https://fakehub.test/")) {
                ParsedMergeRequestUrl(platform = id, baseUrl = "https://fakehub.test", projectId = "o/r", mrId = "1")
            } else {
                null
            }

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
        ) {}

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
                labels = emptyList(),
                changes = emptyList(),
                commits = emptyList(),
                approvals = emptyList(),
                reviewers = emptyList(),
            )
    }

    @Test
    fun `pr url resolution carries the parsed project and mr ids`() =
        runTest {
            val registry = ModuleRegistryImpl().apply { register(FakeHub) }
            val resolution = ValidateModeResolver(
                mrDataPath = null,
                mrId = null,
                platform = null,
                project = null,
                prUrl = "https://fakehub.test/o/r/pull/1",
                noAutoDetect = true,
                logger = NoOpLogger,
                readMrFileContent = { error("file mode not used") },
                providerRuntime = ProviderRuntime(ProviderResolver()),
            ).resolve(registry, emptySet())

            assertEquals("o/r" to "1", resolution.projectId to resolution.mrId)
        }
}
