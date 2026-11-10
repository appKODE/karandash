package io.karandash.cli.detect

import io.karandash.detect.GitRemoteParser
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
import kotlin.test.assertNull

class GitRemoteParserTest {
    private val parser = GitRemoteParser(
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
    fun `parse GitHub HTTPS URL`() {
        runTest {
            val result = parser.parse("https://github.com/owner/repo.git")
            assertEquals("github" to "owner/repo", result)
        }
    }

    @Test
    fun `parse GitHub HTTPS URL without git suffix`() {
        runTest {
            val result = parser.parse("https://github.com/owner/repo")
            assertEquals("github" to "owner/repo", result)
        }
    }

    @Test
    fun `parse GitHub SSH URL`() {
        runTest {
            val result = parser.parse("git@github.com:owner/repo.git")
            assertEquals("github" to "owner/repo", result)
        }
    }

    @Test
    fun `parse GitHub SSH URL without git suffix`() {
        runTest {
            val result = parser.parse("git@github.com:owner/repo")
            assertEquals("github" to "owner/repo", result)
        }
    }

    @Test
    fun `parse GitLab HTTPS URL`() {
        runTest {
            val result = parser.parse("https://gitlab.com/owner/repo.git")
            assertEquals("gitlab" to "owner/repo", result)
        }
    }

    @Test
    fun `parse GitLab SSH URL`() {
        runTest {
            val result = parser.parse("git@gitlab.com:owner/repo.git")
            assertEquals("gitlab" to "owner/repo", result)
        }
    }

    @Test
    fun `parse Bitbucket HTTPS URL`() {
        runTest {
            val result = parser.parse("https://bitbucket.org/owner/repo.git")
            assertEquals("bitbucket" to "owner/repo", result)
        }
    }

    @Test
    fun `parse Bitbucket SSH URL`() {
        runTest {
            val result = parser.parse("git@bitbucket.org:owner/repo.git")
            assertEquals("bitbucket" to "owner/repo", result)
        }
    }

    @Test
    fun `parse Azure DevOps HTTPS URL`() {
        runTest {
            val result = parser.parse("https://dev.azure.com/org/project/_git/repo")
            assertEquals("azure-devops" to "org/project/repo", result)
        }
    }

    @Test
    fun `parse Azure DevOps HTTPS URL with git suffix`() {
        runTest {
            val result = parser.parse("https://dev.azure.com/org/project/_git/repo.git")
            assertEquals("azure-devops" to "org/project/repo", result)
        }
    }

    @Test
    fun `parse Azure DevOps SSH URL`() {
        runTest {
            val result = parser.parse("git@ssh.dev.azure.com:v3/org/project/repo")
            assertEquals("azure-devops" to "org/project/repo", result)
        }
    }

    @Test
    fun `parse Azure DevOps SSH URL with git suffix`() {
        runTest {
            val result = parser.parse("git@ssh.dev.azure.com:v3/org/project/repo.git")
            assertEquals("azure-devops" to "org/project/repo", result)
        }
    }

    @Test
    fun `parse Gerrit HTTPS URL`() {
        runTest {
            val result = parser.parse("https://gerrit.example.com/myrepo")
            assertEquals("gerrit" to "myrepo", result)
        }
    }

    @Test
    fun `parse Gerrit HTTPS URL with a prefix`() {
        runTest {
            val result = parser.parse("https://gerrit.example.com/a/myrepo.git")
            assertEquals("gerrit" to "myrepo", result)
        }
    }

    @Test
    fun `parse Gerrit SSH URL`() {
        runTest {
            val result = parser.parse("ssh://gerrit.example.com:29418/myrepo")
            assertEquals("gerrit" to "myrepo", result)
        }
    }

    @Test
    fun `parse Gerrit SSH URL with git suffix`() {
        runTest {
            val result = parser.parse("ssh://gerrit.example.com:29418/myrepo.git")
            assertEquals("gerrit" to "myrepo", result)
        }
    }

    @Test
    fun `parse Gitea HTTPS URL`() {
        runTest {
            val result = parser.parse("https://gitea.com/owner/repo.git")
            assertEquals("gitea" to "owner/repo", result)
        }
    }

    @Test
    fun `parse Gitea SSH URL`() {
        runTest {
            val result = parser.parse("git@gitea.com:owner/repo.git")
            assertEquals("gitea" to "owner/repo", result)
        }
    }

    @Test
    fun `parse CodeCommit HTTPS URL`() {
        runTest {
            val result = parser.parse("https://git-codecommit.us-east-1.amazonaws.com/v1/repos/myrepo")
            assertEquals("awscodecommit" to "myrepo", result)
        }
    }

    @Test
    fun `parse CodeCommit HTTPS URL with git suffix`() {
        runTest {
            val result = parser.parse("https://git-codecommit.us-east-1.amazonaws.com/v1/repos/myrepo.git")
            assertEquals("awscodecommit" to "myrepo", result)
        }
    }

    @Test
    fun `parse returns null for invalid URL`() {
        runTest {
            val result = parser.parse("not-a-git-url")
            assertNull(result)
        }
    }

    @Test
    fun `parse returns null for empty string`() {
        runTest {
            val result = parser.parse("")
            assertNull(result)
        }
    }

    @Test
    fun `parse handles URLs with trailing whitespace`() {
        runTest {
            val result = parser.parse("  https://github.com/owner/repo.git  ")
            assertEquals("github" to "owner/repo", result)
        }
    }

    @Test
    fun `parse HTTP URLs are also supported`() {
        runTest {
            val result = parser.parse("http://github.com/owner/repo.git")
            assertEquals("github" to "owner/repo", result)
        }
    }
}
