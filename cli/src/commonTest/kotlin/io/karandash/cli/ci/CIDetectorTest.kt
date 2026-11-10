package io.karandash.cli.ci

import io.karandash.detect.CIDetector
import io.karandash.platforms.bitbucket.BitbucketPlatformService
import io.karandash.platforms.github.GitHubPlatformService
import io.karandash.platforms.gitlab.GitLabPlatformService
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CIDetectorTest {
    private val detector = CIDetector(
        listOf(GitHubPlatformService(), GitLabPlatformService(), BitbucketPlatformService())
    )

    @Test
    fun `detects GitHub Actions environment`() {
        runTest {
            val env = mapOf(
                "GITHUB_ACTIONS" to "true",
                "GITHUB_REPOSITORY" to "octocat/hello-world",
                "GITHUB_REF" to "refs/pull/42/merge",
                "GITHUB_TOKEN" to "test-github-token-xxx"
            )

            val result = detector.detect { env[it] }

            assertEquals("github", result?.platform)
            assertEquals("octocat/hello-world", result?.projectId)
            assertEquals("42", result?.mrId)
            assertEquals("test-github-token-xxx", result?.token)
            assertEquals("https://api.github.com", result?.baseUrl)
        }
    }

    @Test
    fun `GitHub Actions with branch ref returns null mrId`() {
        runTest {
            val env = mapOf(
                "GITHUB_ACTIONS" to "true",
                "GITHUB_REPOSITORY" to "octocat/hello-world",
                "GITHUB_REF" to "refs/heads/main",
                "GITHUB_TOKEN" to "test-github-token-xxx"
            )

            val result = detector.detect { env[it] }

            assertEquals("github", result?.platform)
            assertNull(result?.mrId)
        }
    }

    @Test
    fun `detects GitLab CI environment`() {
        runTest {
            val env = mapOf(
                "GITLAB_CI" to "true",
                "CI_PROJECT_PATH" to "group/repo",
                "CI_PROJECT_ID" to "12345",
                "CI_MERGE_REQUEST_IID" to "67",
                "CI_JOB_TOKEN" to "gitlab_token",
                "CI_SERVER_URL" to "https://gitlab.example.com"
            )

            val result = detector.detect { env[it] }

            assertEquals("gitlab", result?.platform)
            assertEquals("12345", result?.projectId)
            assertEquals("67", result?.mrId)
            assertEquals("gitlab_token", result?.token)
            assertEquals("https://gitlab.example.com", result?.baseUrl)
        }
    }

    @Test
    fun `GitLab CI uses default URL when CI_SERVER_URL not set`() {
        runTest {
            val env = mapOf(
                "GITLAB_CI" to "true",
                "CI_PROJECT_ID" to "12345",
                "CI_MERGE_REQUEST_IID" to "67"
            )

            val result = detector.detect { env[it] }

            assertEquals("https://gitlab.com", result?.baseUrl)
        }
    }

    @Test
    fun `GitLab CI derives base URL from CI_API_V4_URL`() {
        runTest {
            val env = mapOf(
                "GITLAB_CI" to "true",
                "CI_PROJECT_ID" to "12345",
                "CI_MERGE_REQUEST_IID" to "67",
                "CI_API_V4_URL" to "https://gitlab.internal/api/v4"
            )

            val result = detector.detect { env[it] }

            assertEquals("https://gitlab.internal", result?.baseUrl)
        }
    }

    @Test
    fun `detects Bitbucket Pipelines environment`() {
        runTest {
            val env = mapOf(
                "BITBUCKET_BUILD_NUMBER" to "123",
                "BITBUCKET_REPO_FULL_NAME" to "myteam/myrepo",
                "BITBUCKET_PR_ID" to "45",
                "BITBUCKET_ACCESS_TOKEN" to "bitbucket_token"
            )

            val result = detector.detect { env[it] }

            assertEquals("bitbucket", result?.platform)
            assertEquals("myteam/myrepo", result?.projectId)
            assertEquals("45", result?.mrId)
            assertEquals("bitbucket_token", result?.token)
        }
    }

    @Test
    fun `returns null when no CI environment detected`() {
        runTest {
            val env = emptyMap<String, String>()

            val result = detector.detect { env[it] }

            assertNull(result)
        }
    }

    @Test
    fun `GitHub Actions takes precedence over other CI vars`() {
        runTest {
            val env = mapOf(
                "GITHUB_ACTIONS" to "true",
                "GITHUB_REPOSITORY" to "octocat/repo",
                "GITLAB_CI" to "true",
                "CI_PROJECT_ID" to "999"
            )

            val result = detector.detect { env[it] }

            assertEquals("github", result?.platform)
            assertEquals("octocat/repo", result?.projectId)
        }
    }

    @Test
    fun `GITHUB_REF parsing handles various formats`() {
        runTest {
            val testCases = mapOf(
                "refs/pull/123/merge" to "123",
                "refs/pull/1/merge" to "1",
                "refs/heads/main" to null,
                "refs/heads/feature/branch" to null,
                "refs/tags/v1.0.0" to null
            )

            testCases.forEach { (ref, expectedMrId) ->
                val env = mapOf(
                    "GITHUB_ACTIONS" to "true",
                    "GITHUB_REPOSITORY" to "owner/repo",
                    "GITHUB_REF" to ref
                )

                val result = detector.detect { env[it] }
                assertEquals(expectedMrId, result?.mrId, "Failed for GITHUB_REF=$ref")
            }
        }
    }

    @Test
    fun `GitHub Actions without GITHUB_REF returns null mrId`() {
        runTest {
            val env = mapOf(
                "GITHUB_ACTIONS" to "true",
                "GITHUB_REPOSITORY" to "owner/repo",
                "GITHUB_TOKEN" to "token"
            )

            val result = detector.detect { env[it] }

            assertEquals("github", result?.platform)
            assertNull(result?.mrId)
        }
    }

    @Test
    fun `empty providers list returns null`() {
        runTest {
            val emptyDetector = CIDetector(emptyList())
            val env = mapOf("GITHUB_ACTIONS" to "true", "GITHUB_REPOSITORY" to "owner/repo")

            val result = emptyDetector.detect { env[it] }

            assertNull(result)
        }
    }

    @Test
    fun `blank CI variables count as unset`() {
        runTest {
            val env = mapOf(
                "GITLAB_CI" to "",
                "BITBUCKET_BUILD_NUMBER" to "",
                "BITBUCKET_REPO_FULL_NAME" to "",
                "BITBUCKET_PR_ID" to "",
            )

            assertNull(detector.detect { env[it] })
        }
    }
}
