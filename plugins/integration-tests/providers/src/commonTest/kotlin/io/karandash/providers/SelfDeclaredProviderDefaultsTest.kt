package io.karandash.providers

import io.karandash.api.finding.MessageCode
import io.karandash.platforms.awscodecommit.AwsCodeCommitPlatformService
import io.karandash.platforms.azuredevops.AzureDevOpsPlatformService
import io.karandash.platforms.bitbucket.BitbucketPlatformService
import io.karandash.platforms.gerrit.GerritPlatformService
import io.karandash.platforms.gitea.GiteaPlatformService
import io.karandash.platforms.github.GitHubPlatformService
import io.karandash.platforms.gitlab.GitLabPlatformService
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelfDeclaredProviderDefaultsTest {
    @Test
    fun `each provider has the expected defaultPriority`() =
        runTest {
            val gitlab = GitLabPlatformService()
            val github = GitHubPlatformService()
            val bitbucket = BitbucketPlatformService()
            val gerrit = GerritPlatformService()
            val azureDevOps = AzureDevOpsPlatformService()
            val aws = AwsCodeCommitPlatformService()
            val gitea = GiteaPlatformService()

            assertTrue(gitlab.defaultPriority == 10, "GitLab defaultPriority should be 10")
            assertTrue(github.defaultPriority == 20, "GitHub defaultPriority should be 20")
            assertTrue(bitbucket.defaultPriority == 30, "Bitbucket defaultPriority should be 30")
            assertTrue(gerrit.defaultPriority == 40, "Gerrit defaultPriority should be 40")
            assertTrue(azureDevOps.defaultPriority == 50, "AzureDevOps defaultPriority should be 50")
            assertTrue(aws.defaultPriority == 60, "AwsCodeCommit defaultPriority should be 60")
            assertTrue(gitea.defaultPriority == 70, "Gitea defaultPriority should be 70")

            gitlab.shutdown()
            github.shutdown()
            bitbucket.shutdown()
            gerrit.shutdown()
            azureDevOps.shutdown()
            aws.shutdown()
            gitea.shutdown()
        }

    @Test
    fun `provider priorities are in ascending order`() =
        runTest {
            val priorities = listOf(
                GitLabPlatformService().also { it.shutdown() }.defaultPriority,
                GitHubPlatformService().also { it.shutdown() }.defaultPriority,
                BitbucketPlatformService().also { it.shutdown() }.defaultPriority,
                GerritPlatformService().also { it.shutdown() }.defaultPriority,
                AzureDevOpsPlatformService().also { it.shutdown() }.defaultPriority,
                AwsCodeCommitPlatformService().also { it.shutdown() }.defaultPriority,
                GiteaPlatformService().also { it.shutdown() }.defaultPriority,
            )

            for (i in 0 until priorities.size - 1) {
                assertTrue(
                    priorities[i] < priorities[i + 1],
                    "Priority at index $i (${priorities[i]}) must be less than priority at index ${i + 1} (${priorities[i + 1]})",
                )
            }
        }

    @Test
    fun `each provider declares a MISSING_AUTH message`() =
        runTest {
            val providers = listOf(
                GitLabPlatformService(),
                GitHubPlatformService(),
                BitbucketPlatformService(),
                GerritPlatformService(),
                AzureDevOpsPlatformService(),
                AwsCodeCommitPlatformService(),
                GiteaPlatformService(),
            )

            providers.forEach { provider ->
                val msg = provider.messages().firstOrNull { it.code == MessageCode.MISSING_AUTH }
                assertNotNull(msg, "MISSING_AUTH message must not be null for ${provider.id}")
            }

            providers.forEach { it.shutdown() }
        }

    @Test
    fun `each provider MISSING_AUTH message contains the provider name`() =
        runTest {
            @Serializable
            data class ProviderEntry(
                val name: String,
                val message: String,
            )

            val entries = listOf(
                ProviderEntry(
                    "GitLab",
                    GitLabPlatformService()
                        .also { it.shutdown() }
                        .messages()
                        .first { it.code == MessageCode.MISSING_AUTH }
                        .text,
                ),
                ProviderEntry(
                    "GitHub",
                    GitHubPlatformService()
                        .also { it.shutdown() }
                        .messages()
                        .first { it.code == MessageCode.MISSING_AUTH }
                        .text,
                ),
                ProviderEntry(
                    "Bitbucket",
                    BitbucketPlatformService()
                        .also { it.shutdown() }
                        .messages()
                        .first { it.code == MessageCode.MISSING_AUTH }
                        .text,
                ),
                ProviderEntry(
                    "Gerrit",
                    GerritPlatformService()
                        .also { it.shutdown() }
                        .messages()
                        .first { it.code == MessageCode.MISSING_AUTH }
                        .text,
                ),
                ProviderEntry(
                    "Azure",
                    AzureDevOpsPlatformService()
                        .also { it.shutdown() }
                        .messages()
                        .first { it.code == MessageCode.MISSING_AUTH }
                        .text,
                ),
                ProviderEntry(
                    "AWS",
                    AwsCodeCommitPlatformService()
                        .also { it.shutdown() }
                        .messages()
                        .first { it.code == MessageCode.MISSING_AUTH }
                        .text,
                ),
                ProviderEntry(
                    "Gitea",
                    GiteaPlatformService()
                        .also { it.shutdown() }
                        .messages()
                        .first { it.code == MessageCode.MISSING_AUTH }
                        .text,
                ),
            )

            entries.forEach { (name, message) ->
                assertTrue(
                    message.contains(name, ignoreCase = true),
                    "MISSING_AUTH message for $name provider should contain '$name' but was: $message",
                )
            }
        }

    @Test
    fun `public cloud providers have non-null defaultApiUrl`() =
        runTest {
            assertNotNull(GitLabPlatformService().also { it.shutdown() }.defaultApiUrl())
            assertNotNull(GitHubPlatformService().also { it.shutdown() }.defaultApiUrl())
            assertNotNull(BitbucketPlatformService().also { it.shutdown() }.defaultApiUrl())
            assertNotNull(AzureDevOpsPlatformService().also { it.shutdown() }.defaultApiUrl())
        }

    @Test
    fun `public cloud provider defaultApiUrls are non-empty strings`() =
        runTest {
            listOf(
                GitLabPlatformService().also { it.shutdown() },
                GitHubPlatformService().also { it.shutdown() },
                BitbucketPlatformService().also { it.shutdown() },
                AzureDevOpsPlatformService().also { it.shutdown() },
            ).forEach { provider ->
                val url = provider.defaultApiUrl()
                assertTrue(url != null && url.isNotEmpty(), "defaultApiUrl() must be non-empty for ${provider.id}")
            }
        }

    @Test
    fun `self-hosted providers have null defaultApiUrl`() =
        runTest {
            assertNull(GerritPlatformService().also { it.shutdown() }.defaultApiUrl())
            assertNull(GiteaPlatformService().also { it.shutdown() }.defaultApiUrl())
            assertNull(AwsCodeCommitPlatformService().also { it.shutdown() }.defaultApiUrl())
        }

    @Test
    fun `AzureDevOps dslName is azureDevOps`() =
        runTest {
            assertEquals("azureDevOps", AzureDevOpsPlatformService().also { it.shutdown() }.dslName)
        }

    @Test
    fun `AwsCodeCommit dslName is awsCodeCommit`() =
        runTest {
            assertEquals("awsCodeCommit", AwsCodeCommitPlatformService().also { it.shutdown() }.dslName)
        }

    @Test
    fun `other providers dslName defaults to id`() =
        runTest {
            assertEquals("gitlab", GitLabPlatformService().also { it.shutdown() }.dslName)
            assertEquals("github", GitHubPlatformService().also { it.shutdown() }.dslName)
            assertEquals("bitbucket", BitbucketPlatformService().also { it.shutdown() }.dslName)
            assertEquals("gerrit", GerritPlatformService().also { it.shutdown() }.dslName)
            assertEquals("gitea", GiteaPlatformService().also { it.shutdown() }.dslName)
        }
}
