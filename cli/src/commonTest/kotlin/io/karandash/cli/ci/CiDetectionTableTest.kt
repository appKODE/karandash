package io.karandash.cli.ci

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.platform.CIEnvironment
import io.karandash.detect.CIDetector
import io.karandash.platforms.awscodecommit.AwsCodeCommitPlatformService
import io.karandash.platforms.azuredevops.AzureDevOpsPlatformService
import io.karandash.platforms.bitbucket.BitbucketPlatformService
import io.karandash.platforms.gerrit.GerritPlatformService
import io.karandash.platforms.gitea.GiteaPlatformService
import io.karandash.platforms.github.GitHubPlatformService
import io.karandash.platforms.gitlab.GitLabPlatformService
import io.karandash.platforms.jenkins.JenkinsProvider
import io.karandash.platforms.localgit.LocalGitProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One row per CI system: the environment a real build of that system exports, and the platform, project, merge
 * request and base URL detection must name, with every first-party platform registered at once.
 */
class CiDetectionTableTest {
    private class Row(
        val name: String,
        val env: Map<String, String>,
        val expected: CIEnvironment?,
    )

    private val rows = listOf(
        Row(
            "GitHub Actions",
            mapOf("GITHUB_ACTIONS" to "true", "GITHUB_REPOSITORY" to "o/r", "GITHUB_REF" to "refs/pull/7/merge", "GITHUB_TOKEN" to "t"),
            CIEnvironment("github", "o/r", "7", "t", "https://api.github.com"),
        ),
        Row(
            "GitHub Actions on the head ref",
            mapOf("GITHUB_ACTIONS" to "true", "GITHUB_REPOSITORY" to "o/r", "GITHUB_REF" to "refs/pull/42/head", "GITHUB_TOKEN" to "t"),
            CIEnvironment("github", "o/r", "42", "t", "https://api.github.com"),
        ),
        Row(
            "GitHub Actions on a branch ref with a pull number in it",
            mapOf("GITHUB_ACTIONS" to "true", "GITHUB_REPOSITORY" to "o/r", "GITHUB_REF" to "refs/heads/x/refs/pull/9/merge"),
            CIEnvironment("github", "o/r", null, null, "https://api.github.com"),
        ),
        Row(
            "Gitea Actions",
            mapOf(
                "GITHUB_ACTIONS" to "true",
                "GITEA_ACTIONS" to "true",
                "GITHUB_SERVER_URL" to "https://gitea.example.com",
                "GITHUB_REPOSITORY" to "o/r",
                "GITHUB_REF" to "refs/pull/8/head",
                "GITHUB_TOKEN" to "t",
            ),
            CIEnvironment("gitea", "o/r", "8", "t", "https://gitea.example.com"),
        ),
        Row(
            "Forgejo Actions",
            mapOf(
                "GITHUB_ACTIONS" to "true",
                "FORGEJO_ACTIONS" to "true",
                "GITHUB_SERVER_URL" to "https://codeberg.org",
                "GITHUB_REPOSITORY" to "o/r",
                "GITHUB_REF" to "refs/heads/main",
            ),
            CIEnvironment("gitea", "o/r", null, null, "https://codeberg.org"),
        ),
        Row(
            "GitLab CI",
            mapOf(
                "GITLAB_CI" to "true",
                "CI_PROJECT_ID" to "42",
                "CI_MERGE_REQUEST_IID" to "3",
                "CI_SERVER_URL" to "https://gl.example.com"
            ),
            CIEnvironment("gitlab", "42", "3", null, "https://gl.example.com"),
        ),
        Row(
            "Bitbucket Pipelines",
            mapOf("BITBUCKET_BUILD_NUMBER" to "1", "BITBUCKET_REPO_FULL_NAME" to "w/r", "BITBUCKET_PR_ID" to "5"),
            CIEnvironment("bitbucket", "w/r", "5", null, "https://api.bitbucket.org/2.0"),
        ),
        Row(
            "Azure Pipelines",
            mapOf(
                "TF_BUILD" to "True",
                "SYSTEM_TEAMFOUNDATIONCOLLECTIONURI" to "https://dev.azure.com/org/",
                "SYSTEM_TEAMPROJECT" to "proj",
                "BUILD_REPOSITORY_NAME" to "repo",
                "SYSTEM_PULLREQUEST_PULLREQUESTID" to "9",
            ),
            CIEnvironment("azure-devops", "org/proj/repo", "9", null, "https://dev.azure.com/org/proj/_apis"),
        ),
        Row(
            "Gerrit Trigger modern change URL",
            mapOf(
                "GERRIT_CHANGE_URL" to "https://review.example.com/c/proj/+/123",
                "GERRIT_HOST" to "review.example.com",
                "GERRIT_SCHEME" to "ssh",
                "GERRIT_PROJECT" to "proj",
                "GERRIT_CHANGE_NUMBER" to "123",
            ),
            CIEnvironment("gerrit", "proj", "123", null, "https://review.example.com"),
        ),
        Row(
            "Gerrit Trigger legacy change URL with prefix",
            mapOf("GERRIT_CHANGE_URL" to "https://host.example.com/gerrit/456", "GERRIT_PROJECT" to "p", "GERRIT_CHANGE_NUMBER" to "456"),
            CIEnvironment("gerrit", "p", "456", null, "https://host.example.com/gerrit"),
        ),
        Row(
            "Gerrit Trigger host only",
            mapOf("GERRIT_HOST" to "review.example.com", "GERRIT_SCHEME" to "ssh", "GERRIT_PROJECT" to "p"),
            CIEnvironment("gerrit", "p", null, null, "https://review.example.com"),
        ),
        Row(
            "Woodpecker on Gitea",
            mapOf(
                "CI" to "woodpecker",
                "CI_FORGE_TYPE" to "gitea",
                "CI_FORGE_URL" to "https://gitea.example.com/",
                "CI_REPO" to "o/r",
                "CI_COMMIT_PULL_REQUEST" to "11",
            ),
            CIEnvironment("gitea", "o/r", "11", null, "https://gitea.example.com"),
        ),
        Row(
            "Woodpecker on GitHub Enterprise",
            mapOf("CI" to "woodpecker", "CI_FORGE_TYPE" to "github", "CI_FORGE_URL" to "https://ghe.example.com", "CI_REPO" to "o/r"),
            CIEnvironment("github", "o/r", null, null, "https://ghe.example.com/api/v3"),
        ),
        Row(
            "Woodpecker on GitLab",
            mapOf(
                "CI" to "woodpecker",
                "CI_FORGE_TYPE" to "gitlab",
                "CI_FORGE_URL" to "https://gitlab.com",
                "CI_REPO" to "g/p",
                "CI_COMMIT_PULL_REQUEST" to "2",
            ),
            CIEnvironment("gitlab", "g/p", "2", null, "https://gitlab.com"),
        ),
        Row(
            "CodeBuild with a CodeCommit source",
            mapOf(
                "AWS_REGION" to "eu-west-1",
                "CODEBUILD_BUILD_ID" to "b:1",
                "CODEBUILD_SOURCE_REPO_URL" to "https://git-codecommit.eu-west-1.amazonaws.com/v1/repos/repo",
                "CODEBUILD_SOURCE_VERSION" to "pr/4",
            ),
            CIEnvironment("awscodecommit", "repo", "4", null, "https://codecommit.eu-west-1.amazonaws.com"),
        ),
        Row(
            "CodeBuild with a GitHub source",
            mapOf(
                "AWS_REGION" to "eu-west-1",
                "CODEBUILD_BUILD_ID" to "b:1",
                "CODEBUILD_SOURCE_REPO_URL" to "https://github.com/o/r.git",
                "CODEBUILD_WEBHOOK_TRIGGER" to "pr/15",
            ),
            CIEnvironment("github", "o/r", "15", null, "https://api.github.com"),
        ),
        Row(
            "CodeBuild with a Bitbucket source",
            mapOf(
                "CODEBUILD_BUILD_ID" to "b:1",
                "CODEBUILD_SOURCE_REPO_URL" to "https://bitbucket.org/w/r.git",
                "CODEBUILD_SOURCE_VERSION" to "pr/6",
            ),
            CIEnvironment("bitbucket", "w/r", "6", null, "https://api.bitbucket.org/2.0"),
        ),
        Row("No CI", mapOf("HOME" to "/home/u"), null),
    )

    @Test
    fun `every CI system is detected as its own platform`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val providers = listOf(
                GitLabPlatformService(),
                GitHubPlatformService(),
                BitbucketPlatformService(),
                LocalGitProvider(),
                AzureDevOpsPlatformService(),
                GerritPlatformService(),
                GiteaPlatformService(),
                AwsCodeCommitPlatformService(),
                JenkinsProvider(registry),
            )
            providers.forEach { registry.register(it) }
            val detector = CIDetector(providers)
            val failures = rows.mapNotNull { row ->
                val actual = detector.detect { row.env[it] }
                if (actual == row.expected) null else "${row.name}: expected ${row.expected}, got $actual"
            }
            assertEquals(emptyList(), failures)
        }

    @Test
    fun `Jenkins names the platform of its change URL`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val github = GitHubPlatformService()
            registry.register(github)
            val detector = CIDetector(listOf(github, JenkinsProvider(registry)))
            val env = mapOf("JENKINS_URL" to "https://ci.example.com/", "CHANGE_URL" to "https://github.com/o/r/pull/21")
            val actual = detector.detect { env[it] }
            assertEquals("github", actual?.platform)
            assertEquals("o/r", actual?.projectId)
            assertEquals("21", actual?.mrId)
        }
}
