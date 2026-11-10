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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PrUrlParserTest {
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
    fun `parse GitLab URL with gitlab dot com`() {
        runTest {
            val url = "https://gitlab.com/group/project/-/merge_requests/123"
            val parsed = parser.parse(url)

            assertNotNull(parsed)
            assertEquals("gitlab", parsed.platform)
            assertEquals("https://gitlab.com", parsed.baseUrl)
            assertEquals("group/project", parsed.projectId)
            assertEquals("123", parsed.mrId)
        }
    }

    @Test
    fun `parse GitLab URL with nested groups`() {
        runTest {
            val url = "https://gitlab.com/group/subgroup/project/-/merge_requests/456"
            val parsed = parser.parse(url)

            assertNotNull(parsed)
            assertEquals("gitlab", parsed.platform)
            assertEquals("group/subgroup/project", parsed.projectId)
            assertEquals("456", parsed.mrId)
        }
    }

    @Test
    fun `parse GitLab URL with custom hostname`() {
        runTest {
            val url = "https://gitlab.example.com/team/repo/-/merge_requests/789"
            val parsed = parser.parse(url)

            assertNotNull(parsed)
            assertEquals("gitlab", parsed.platform)
            assertEquals("https://gitlab.example.com", parsed.baseUrl)
            assertEquals("team/repo", parsed.projectId)
            assertEquals("789", parsed.mrId)
        }
    }

    @Test
    fun `parse GitHub URL with github dot com`() {
        runTest {
            val url = "https://github.com/owner/repo/pull/42"
            val parsed = parser.parse(url)

            assertNotNull(parsed)
            assertEquals("github", parsed.platform)
            assertEquals("https://api.github.com", parsed.baseUrl)
            assertEquals("owner/repo", parsed.projectId)
            assertEquals("42", parsed.mrId)
        }
    }

    @Test
    fun `parse GitHub URL with http protocol`() {
        runTest {
            val url = "http://github.com/owner/repo/pull/99"
            val parsed = parser.parse(url)

            assertNotNull(parsed)
            assertEquals("github", parsed.platform)
            assertEquals("owner/repo", parsed.projectId)
            assertEquals("99", parsed.mrId)
        }
    }

    @Test
    fun `parse GitHub Enterprise URL`() {
        runTest {
            val url = "https://github.enterprise.com/owner/repo/pull/100"
            val parsed = parser.parse(url)

            assertNotNull(parsed)
            assertEquals("github", parsed.platform)
            assertEquals("https://github.enterprise.com/api/v3", parsed.baseUrl)
            assertEquals("owner/repo", parsed.projectId)
            assertEquals("100", parsed.mrId)
        }
    }

    @Test
    fun `parse Bitbucket URL`() {
        runTest {
            val url = "https://bitbucket.org/workspace/repo-slug/pull-requests/55"
            val parsed = parser.parse(url)

            assertNotNull(parsed)
            assertEquals("bitbucket", parsed.platform)
            assertEquals("https://api.bitbucket.org/2.0", parsed.baseUrl)
            assertEquals("workspace/repo-slug", parsed.projectId)
            assertEquals("55", parsed.mrId)
        }
    }

    @Test
    fun `parse Azure DevOps URL`() {
        runTest {
            val url = "https://dev.azure.com/myorg/myproject/_git/myrepo/pullrequest/12"
            val parsed = parser.parse(url)

            assertNotNull(parsed)
            assertEquals("azure-devops", parsed.platform)
            assertEquals("https://dev.azure.com", parsed.baseUrl)
            assertEquals("myorg/myproject/myrepo", parsed.projectId)
            assertEquals("12", parsed.mrId)
        }
    }

    @Test
    fun `parse Gitea URL with custom hostname`() {
        runTest {
            val url = "https://gitea.example.com/owner/repo/pulls/88"
            val parsed = parser.parse(url)

            assertNotNull(parsed)
            assertEquals("gitea", parsed.platform)
            assertEquals("https://gitea.example.com", parsed.baseUrl)
            assertEquals("owner/repo", parsed.projectId)
            assertEquals("88", parsed.mrId)
        }
    }

    @Test
    fun `parse Gitea URL with self-hosted instance`() {
        runTest {
            val url = "https://code.company.com/team/project/pulls/7"
            val parsed = parser.parse(url)

            assertNotNull(parsed)
            assertEquals("gitea", parsed.platform)
            assertEquals("https://code.company.com", parsed.baseUrl)
            assertEquals("team/project", parsed.projectId)
            assertEquals("7", parsed.mrId)
        }
    }

    @Test
    fun `parse Gerrit URL with modern format`() {
        runTest {
            val url = "https://gerrit.example.com/c/myproject/+/12345"
            val parsed = parser.parse(url)

            assertNotNull(parsed)
            assertEquals("gerrit", parsed.platform)
            assertEquals("https://gerrit.example.com", parsed.baseUrl)
            assertEquals("myproject", parsed.projectId)
            assertEquals("12345", parsed.mrId)
        }
    }

    @Test
    fun `parse Gerrit URL with nested project`() {
        runTest {
            val url = "https://review.company.com/c/team%2Fproject/+/999"
            val parsed = parser.parse(url)

            assertNotNull(parsed)
            assertEquals("gerrit", parsed.platform)
            assertEquals("https://review.company.com", parsed.baseUrl)
            assertEquals("team%2Fproject", parsed.projectId)
            assertEquals("999", parsed.mrId)
        }
    }

    @Test
    fun `parse AWS CodeCommit URL with standard format`() {
        runTest {
            val url = "https://console.aws.amazon.com/codesuite/codecommit/repositories/my-repo/pull-requests/3"
            val parsed = parser.parse(url)

            assertNotNull(parsed)
            assertEquals("awscodecommit", parsed.platform)
            assertEquals("my-repo", parsed.projectId)
            assertEquals("3", parsed.mrId)
        }
    }

    @Test
    fun `parse AWS CodeCommit URL with regional format`() {
        runTest {
            val url = "https://us-west-2.console.aws.amazon.com/codesuite/codecommit/repositories/my-repo/pull-requests/5"
            val parsed = parser.parse(url)

            assertNotNull(parsed)
            assertEquals("awscodecommit", parsed.platform)
            assertEquals("https://codecommit.us-west-2.amazonaws.com", parsed.baseUrl)
            assertEquals("my-repo", parsed.projectId)
            assertEquals("5", parsed.mrId)
        }
    }

    @Test
    fun `parse AWS CodeCommit URL with eu region`() {
        runTest {
            val url = "https://eu-central-1.console.aws.amazon.com/codesuite/codecommit/repositories/test-repo/pull-requests/10"
            val parsed = parser.parse(url)

            assertNotNull(parsed)
            assertEquals("awscodecommit", parsed.platform)
            assertEquals("https://codecommit.eu-central-1.amazonaws.com", parsed.baseUrl)
            assertEquals("test-repo", parsed.projectId)
            assertEquals("10", parsed.mrId)
        }
    }

    @Test
    fun `return null for invalid URL`() {
        runTest {
            val url = "https://example.com/some/random/path"
            val parsed = parser.parse(url)

            assertNull(parsed)
        }
    }

    @Test
    fun `return null for malformed GitLab URL`() {
        runTest {
            val url = "https://gitlab.com/project/merge_requests/123"
            val parsed = parser.parse(url)

            assertNull(parsed)
        }
    }

    @Test
    fun `return null for malformed GitHub URL`() {
        runTest {
            val url = "https://github.com/owner/pull/123"
            val parsed = parser.parse(url)

            assertNull(parsed)
        }
    }

    @Test
    fun `return null for empty string`() {
        runTest {
            val parsed = parser.parse("")

            assertNull(parsed)
        }
    }

    @Test
    fun `return null for URL without protocol`() {
        runTest {
            val url = "gitlab.com/group/project/-/merge_requests/123"
            val parsed = parser.parse(url)

            assertNull(parsed)
        }
    }
}
