package io.karandash.cli.provider

import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.errors.ProviderException
import io.karandash.api.module.PlatformServiceModule
import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.platforms.awscodecommit.AwsCodeCommitPlatformService
import io.karandash.platforms.azuredevops.AzureDevOpsPlatformService
import io.karandash.platforms.bitbucket.BitbucketPlatformService
import io.karandash.platforms.gerrit.GerritPlatformService
import io.karandash.platforms.gitea.GiteaPlatformService
import io.karandash.platforms.github.GitHubPlatformService
import io.karandash.platforms.gitlab.GitLabPlatformService
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProviderResolverTest {
    private val resolver = ProviderResolver()

    private val stubMergeRequest = MergeRequest(
        id = "1",
        iid = 1,
        title = "Test MR",
        description = null,
        sourceBranch = "feature/test",
        targetBranch = "main",
        author = User(username = "tester", name = "Test User", email = "test@example.com"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        commits = listOf(
            Commit(
                sha = "abc123",
                message = "feat: test",
                author = CommitAuthor(name = "Test User", email = "test@example.com"),
                timestamp = "2024-01-01T00:00:00Z"
            )
        ),
        changes = listOf(
            FileChange(
                path = "README.md",
                changeType = ChangeType.MODIFIED
            )
        )
    )

    private suspend fun registryWith(vararg modules: PlatformServiceModule): ModuleRegistry {
        val registry = ModuleRegistryImpl()
        modules.forEach { registry.register(it) }
        return registry
    }

    private fun emptyRegistry(): ModuleRegistry = ModuleRegistryImpl()

    private suspend fun populatedRegistry(): ModuleRegistry =
        registryWith(
            GitLabPlatformService(),
            GitHubPlatformService(),
            BitbucketPlatformService(),
            GerritPlatformService(),
            AzureDevOpsPlatformService(),
            AwsCodeCommitPlatformService(),
            GiteaPlatformService()
        )

    private fun dynamicProvider(
        id: String,
        name: String
    ): PlatformServiceModule =
        object : PlatformServiceModule {
            override val id: String = id
            override val name: String = name
            override val version: String = "1.0.0"
            override val type: ModuleType = ModuleType.PLATFORM_SERVICE

            override suspend fun initialize(context: PluginContext): Boolean = true

            override suspend fun fetchMergeRequest(
                projectId: String,
                mrId: String
            ): MergeRequest = stubMergeRequest.copy(id = mrId)

            override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = stubMergeRequest.copy(id = url)

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
        }

    @Test
    fun `resolve GitLab provider from registry`() =
        runTest {
            val provider = resolver.resolve("gitlab", registryWith(GitLabPlatformService()))
            assertIs<GitLabPlatformService>(provider)
        }

    @Test
    fun `resolve GitHub provider from registry`() =
        runTest {
            val provider = resolver.resolve("github", registryWith(GitHubPlatformService()))
            assertIs<GitHubPlatformService>(provider)
        }

    @Test
    fun `resolve Bitbucket provider from registry`() =
        runTest {
            val provider = resolver.resolve("bitbucket", registryWith(BitbucketPlatformService()))
            assertIs<BitbucketPlatformService>(provider)
        }

    @Test
    fun `resolve Gerrit provider from registry`() =
        runTest {
            val provider = resolver.resolve("gerrit", registryWith(GerritPlatformService()))
            assertIs<GerritPlatformService>(provider)
        }

    @Test
    fun `resolve Azure DevOps provider from registry`() =
        runTest {
            val provider = resolver.resolve("azure-devops", registryWith(AzureDevOpsPlatformService()))
            assertIs<AzureDevOpsPlatformService>(provider)
        }

    @Test
    fun `resolve Azure DevOps provider with azuredevops name`() =
        runTest {
            val provider = resolver.resolve("azuredevops", registryWith(AzureDevOpsPlatformService()))
            assertIs<AzureDevOpsPlatformService>(provider)
        }

    @Test
    fun `resolve AWS CodeCommit provider from registry`() =
        runTest {
            val provider = resolver.resolve("awscodecommit", registryWith(AwsCodeCommitPlatformService()))
            assertIs<AwsCodeCommitPlatformService>(provider)
        }

    @Test
    fun `resolve AWS CodeCommit provider by alias`() =
        runTest {
            val provider = resolver.resolve("codecommit", registryWith(AwsCodeCommitPlatformService()))
            assertIs<AwsCodeCommitPlatformService>(provider)
        }

    @Test
    fun `resolve Gitea provider from registry`() =
        runTest {
            val provider = resolver.resolve("gitea", registryWith(GiteaPlatformService()))
            assertIs<GiteaPlatformService>(provider)
        }

    @Test
    fun `resolve dynamic provider from registry by platform alias`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val providerModule = dynamicProvider("acme-review-provider", "Acme Review Provider")
            registry.register(providerModule)

            val provider = resolver.resolve(
                platform = "acme-review-provider",
                registry = registry
            )
            assertTrue(provider === providerModule)
        }

    @Test
    fun `resolve dynamic provider prefers enabled plugin IDs`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val providerA = dynamicProvider("provider-a", "Provider A GitHub")
            val providerB = dynamicProvider("provider-b", "Provider B GitHub")
            registry.register(providerA)
            registry.register(providerB)

            val provider = resolver.resolve(
                platform = "github",
                registry = registry,
                preferredPluginIds = setOf("provider-b")
            )
            assertTrue(provider === providerB)
        }

    @Test
    fun `resolve preferred dynamic provider even when platform hint does not match module id`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val customProvider = dynamicProvider("custom-provider", "Acme Enterprise Provider")
            registry.register(customProvider)

            val provider = resolver.resolve(
                platform = "gitlab",
                registry = registry,
                preferredPluginIds = setOf("custom-provider")
            )
            assertTrue(provider === customProvider)
        }

    @Test
    fun `resolve dynamic provider fails on ambiguous matches`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(dynamicProvider("team-github-provider", "Team GitHub Provider"))
            registry.register(dynamicProvider("backup-github-provider", "Backup GitHub Provider"))

            val exception = assertFailsWith<ProviderException> {
                resolver.resolve(
                    platform = "github",
                    registry = registry
                )
            }
            assertTrue(exception.message!!.contains("Multiple provider modules"))
        }

    @Test
    fun `case-insensitive platform matching`() =
        runTest {
            val registry = registryWith(GitLabPlatformService())

            val provider1 = resolver.resolve("GitLab", registry)
            assertIs<GitLabPlatformService>(provider1)

            val provider2 = resolver.resolve("GITLAB", registry)
            assertIs<GitLabPlatformService>(provider2)

            val provider3 = resolver.resolve("gitlab", registry)
            assertIs<GitLabPlatformService>(provider3)
        }

    @Test
    fun `resolve Azure DevOps with different casing`() =
        runTest {
            val registry = registryWith(AzureDevOpsPlatformService())

            val provider1 = resolver.resolve("Azure-DevOps", registry)
            assertIs<AzureDevOpsPlatformService>(provider1)

            val provider2 = resolver.resolve("AZURE-DEVOPS", registry)
            assertIs<AzureDevOpsPlatformService>(provider2)
        }

    @Test
    fun `an unknown platform carries the UNKNOWN_PLATFORM code`() {
        for (platform in listOf("gitlab", " ")) {
            val exception = assertFailsWith<ProviderException> { resolver.resolve(platform, emptyRegistry()) }
            assertTrue(exception.message.orEmpty().startsWith("[E0102] Unknown platform"), exception.message)
        }
    }

    @Test
    fun `missing GitLab auth throws ProviderException`() {
        val exception = assertFailsWith<ProviderException> {
            resolver.resolve("gitlab", emptyRegistry())
        }
        assertTrue(exception.message!!.contains("Unknown platform"))
    }

    @Test
    fun `missing GitHub auth throws ProviderException`() {
        val exception = assertFailsWith<ProviderException> {
            resolver.resolve("github", emptyRegistry())
        }
        assertTrue(exception.message!!.contains("Unknown platform"))
    }

    @Test
    fun `missing Bitbucket auth throws ProviderException`() {
        val exception = assertFailsWith<ProviderException> {
            resolver.resolve("bitbucket", emptyRegistry())
        }
        assertTrue(exception.message!!.contains("Unknown platform"))
    }

    @Test
    fun `missing Gerrit auth throws ProviderException`() {
        val exception = assertFailsWith<ProviderException> {
            resolver.resolve("gerrit", emptyRegistry())
        }
        assertTrue(exception.message!!.contains("Unknown platform"))
    }

    @Test
    fun `missing Azure DevOps auth throws ProviderException`() {
        val exception = assertFailsWith<ProviderException> {
            resolver.resolve("azure-devops", emptyRegistry())
        }
        assertTrue(exception.message!!.contains("Unknown platform"))
    }

    @Test
    fun `missing AWS CodeCommit auth throws ProviderException`() {
        val exception = assertFailsWith<ProviderException> {
            resolver.resolve("awscodecommit", emptyRegistry())
        }
        assertTrue(exception.message!!.contains("Unknown platform"))
    }

    @Test
    fun `missing Gitea auth throws ProviderException`() {
        val exception = assertFailsWith<ProviderException> {
            resolver.resolve("gitea", emptyRegistry())
        }
        assertTrue(exception.message!!.contains("Unknown platform"))
    }

    @Test
    fun `registry mode reports provider module not loaded`() {
        val registry = ModuleRegistryImpl()

        val exception = assertFailsWith<ProviderException> {
            resolver.resolve("gitlab", registry = registry)
        }
        assertTrue(exception.message!!.contains("Unknown platform"))
    }

    @Test
    fun `registry mode built-in provider not loaded reports not loaded`() {
        val registry = ModuleRegistryImpl()
        val exception = assertFailsWith<ProviderException> {
            resolver.resolve("gitlab", registry = registry)
        }
        assertTrue(exception.message!!.contains("Unknown platform"))
    }

    @Test
    fun `unknown platform throws ProviderException`() {
        val exception = assertFailsWith<ProviderException> {
            resolver.resolve("unknown-platform", emptyRegistry())
        }
        assertTrue(exception.message!!.contains("Unknown platform"))
        assertTrue(exception.message!!.contains("unknown-platform"))
    }

    @Test
    fun `empty platform throws ProviderException`() {
        val exception = assertFailsWith<ProviderException> {
            resolver.resolve("", emptyRegistry())
        }
        assertTrue(exception.message!!.contains("Unknown platform"))
    }

    @Test
    fun `suggests similar provider names for typos - github`() =
        runTest {
            val exception = assertFailsWith<ProviderException> {
                resolver.resolve("gthub", populatedRegistry())
            }
            assertTrue(
                exception.message!!.contains("Did you mean") && exception.message!!.contains("github"),
                "Should suggest 'github' for 'gthub' typo"
            )
        }

    @Test
    fun `suggests similar provider names for typos - gitlab`() =
        runTest {
            val exception = assertFailsWith<ProviderException> {
                resolver.resolve("gtlab", populatedRegistry())
            }
            assertTrue(
                exception.message!!.contains("Did you mean") && exception.message!!.contains("gitlab"),
                "Should suggest 'gitlab' for 'gtlab' typo"
            )
        }

    @Test
    fun `fuzzy matches short input to gitea provider`() =
        runTest {
            val provider = resolver.resolve("gite", populatedRegistry())
            assertIs<GiteaPlatformService>(provider)
        }

    @Test
    fun `suggests bitbucket for bitbucet typo`() =
        runTest {
            val exception = assertFailsWith<ProviderException> {
                resolver.resolve("bitbucet", populatedRegistry())
            }
            assertTrue(
                exception.message!!.contains("Did you mean") && exception.message!!.contains("bitbucket"),
                "Should suggest 'bitbucket' for 'bitbucet' typo"
            )
        }

    @Test
    fun `no suggestions for distance greater than 3`() {
        val exception = assertFailsWith<ProviderException> {
            resolver.resolve("totallyunknown", emptyRegistry())
        }
        assertTrue(
            !exception.message!!.contains("Did you mean"),
            "Should not suggest when distance > 3"
        )
        assertTrue(
            exception.message!!.contains("Supported platforms"),
            "Should show list of supported platforms instead"
        )
    }

    @Test
    fun `error message includes supported platforms when no suggestions`() =
        runTest {
            val exception = assertFailsWith<ProviderException> {
                resolver.resolve("xyz", populatedRegistry())
            }
            assertTrue(exception.message!!.contains("gitlab"))
            assertTrue(exception.message!!.contains("github"))
            assertTrue(exception.message!!.contains("bitbucket"))
        }
}
