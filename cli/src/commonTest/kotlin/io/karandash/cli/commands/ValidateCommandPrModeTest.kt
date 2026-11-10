package io.karandash.cli.commands

import io.karandash.cli.commands.validate.PrUrlParser
import io.karandash.platforms.awscodecommit.AwsCodeCommitPlatformService
import io.karandash.platforms.azuredevops.AzureDevOpsPlatformService
import io.karandash.platforms.bitbucket.BitbucketPlatformService
import io.karandash.platforms.gerrit.GerritPlatformService
import io.karandash.platforms.gitea.GiteaPlatformService
import io.karandash.platforms.github.GitHubPlatformService
import io.karandash.platforms.gitlab.GitLabPlatformService
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Tests for ValidateCommand --pr flag (PR URL mode)
 *
 * Note: These are integration tests that require actual provider implementations.
 * For unit testing, mock providers would be needed.
 */
class ValidateCommandPrModeTest {
    private val parser = PrUrlParser(
        listOf(
            GitLabPlatformService(),
            GitHubPlatformService(),
            BitbucketPlatformService(),
            AzureDevOpsPlatformService(),
            GerritPlatformService(),
            GiteaPlatformService(),
            AwsCodeCommitPlatformService()
        )
    )

    @Test
    fun `pr flag parses valid GitLab URL`() {
        runTest {
            val url = "https://gitlab.com/group/project/-/merge_requests/123"
            val parsed = parser.parse(url)

            assertTrue(parsed != null, "GitLab URL should parse successfully")
            assertTrue(parsed.platform == "gitlab", "Should identify as GitLab")
            assertTrue(parsed.projectId == "group/project", "Should extract project ID")
            assertTrue(parsed.mrId == "123", "Should extract MR ID")
        }
    }

    @Test
    fun `pr flag parses valid GitHub URL`() {
        runTest {
            val url = "https://github.com/owner/repo/pull/42"
            val parsed = parser.parse(url)

            assertTrue(parsed != null, "GitHub URL should parse successfully")
            assertTrue(parsed.platform == "github", "Should identify as GitHub")
            assertTrue(parsed.projectId == "owner/repo", "Should extract project ID")
            assertTrue(parsed.mrId == "42", "Should extract PR ID")
        }
    }

    @Test
    fun `pr flag rejects invalid URL format`() {
        runTest {
            val url = "https://example.com/invalid/url"
            val parsed = parser.parse(url)

            assertTrue(parsed == null, "Invalid URL should return null")
        }
    }

    @Test
    fun `pr flag works with all supported platforms`() {
        runTest {
            val testUrls = listOf(
                "https://gitlab.com/group/proj/-/merge_requests/1" to "gitlab",
                "https://github.com/owner/repo/pull/2" to "github",
                "https://bitbucket.org/workspace/repo/pull-requests/3" to "bitbucket",
                "https://dev.azure.com/org/proj/_git/repo/pullrequest/4" to "azure-devops",
                "https://gitea.example.com/owner/repo/pulls/5" to "gitea",
                "https://gerrit.example.com/c/project/+/6" to "gerrit",
                "https://console.aws.amazon.com/codesuite/codecommit/repositories/repo/pull-requests/7" to "awscodecommit"
            )

            for ((url, expectedPlatform) in testUrls) {
                val parsed = parser.parse(url)
                assertTrue(parsed != null, "URL should parse: $url")
                assertTrue(parsed.platform == expectedPlatform, "Should identify as $expectedPlatform, got ${parsed.platform}")
            }
        }
    }

    @Test
    fun `pr flag with custom hostname for GitLab`() {
        runTest {
            val url = "https://gitlab.enterprise.com/team/repo/-/merge_requests/100"
            val parsed = parser.parse(url)

            assertTrue(parsed != null, "Custom GitLab URL should parse")
            assertTrue(parsed.platform == "gitlab", "Should identify as GitLab")
            assertTrue(parsed.baseUrl == "https://gitlab.enterprise.com", "Should extract custom base URL")
        }
    }

    @Test
    fun `pr flag with custom hostname for GitHub Enterprise`() {
        runTest {
            val url = "https://github.company.com/owner/repo/pull/200"
            val parsed = parser.parse(url)

            assertTrue(parsed != null, "GitHub Enterprise URL should parse")
            assertTrue(parsed.platform == "github", "Should identify as GitHub")
            assertTrue(parsed.baseUrl == "https://github.company.com/api/v3", "Should construct API base URL")
        }
    }

    @Test
    fun `pr flag with self-hosted Gitea`() {
        runTest {
            val url = "https://code.mycompany.com/team/project/pulls/50"
            val parsed = parser.parse(url)

            assertTrue(parsed != null, "Self-hosted Gitea URL should parse")
            assertTrue(parsed.platform == "gitea", "Should identify as Gitea")
            assertTrue(parsed.baseUrl == "https://code.mycompany.com", "Should extract base URL")
        }
    }

    @Test
    fun `pr flag with AWS regional URL`() {
        runTest {
            val url = "https://us-west-2.console.aws.amazon.com/codesuite/codecommit/repositories/my-repo/pull-requests/8"
            val parsed = parser.parse(url)

            assertTrue(parsed != null, "AWS regional URL should parse")
            assertTrue(parsed.platform == "awscodecommit", "Should identify as AWS CodeCommit")
            assertTrue(parsed.baseUrl.contains("us-west-2"), "Should include region in base URL")
        }
    }
}
