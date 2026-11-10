package io.karandash.cli.detect

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.module.PlatformServiceModule
import io.karandash.detect.DetectionConfidence
import io.karandash.detect.PlatformDetector
import io.karandash.platforms.awscodecommit.AwsCodeCommitPlatformService
import io.karandash.platforms.azuredevops.AzureDevOpsPlatformService
import io.karandash.platforms.bitbucket.BitbucketPlatformService
import io.karandash.platforms.gerrit.GerritPlatformService
import io.karandash.platforms.gitea.GiteaPlatformService
import io.karandash.platforms.github.GitHubPlatformService
import io.karandash.platforms.gitlab.GitLabPlatformService
import io.karandash.platforms.jenkins.JenkinsProvider
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlatformDetectorTest {
    private val providers: List<PlatformServiceModule> = listOf(
        GitLabPlatformService(),
        GitHubPlatformService(),
        BitbucketPlatformService(),
        AzureDevOpsPlatformService(),
        GerritPlatformService(),
        GiteaPlatformService(),
        AwsCodeCommitPlatformService(),
        JenkinsProvider(ModuleRegistryImpl())
    )

    @Test
    fun `detectFromEnv returns HIGH confidence when KARANDASH_PLATFORM is set`() {
        runTest {
            val envMap = mapOf(
                "KARANDASH_PLATFORM" to "github",
                "KARANDASH_PROJECT_ID" to "owner/repo",
                "KARANDASH_MR_ID" to "123",
                "KARANDASH_BASE_URL" to "https://api.github.com",
                "KARANDASH_TOKEN" to "ghp_token123"
            )
            val detector = PlatformDetector(envProvider = envMap::get)

            val result = detector.detectFromEnv()
            assertNotNull(result)
            assertEquals("github", result.platform)
            assertEquals("owner/repo", result.projectId)
            assertEquals("123", result.mrId)
            assertEquals("https://api.github.com", result.baseUrl)
            assertEquals("ghp_token123", result.token)
            assertEquals(DetectionConfidence.HIGH, result.confidence)
            assertEquals("explicit", result.source)
        }
    }

    @Test
    fun `detectFromEnv returns null when KARANDASH_PLATFORM is not set`() {
        runTest {
            val detector = PlatformDetector(envProvider = { null })
            val result = detector.detectFromEnv()
            assertNull(result)
        }
    }

    @Test
    fun `detectFromCi detects GitHub Actions`() {
        runTest {
            val envMap = mapOf(
                "GITHUB_ACTIONS" to "true",
                "GITHUB_REPOSITORY" to "owner/repo",
                "GITHUB_TOKEN" to "ghp_token"
            )
            val detector = PlatformDetector(providers, envProvider = envMap::get)

            val result = detector.detectFromCi()
            assertNotNull(result)
            assertEquals("github", result.platform)
            assertEquals("owner/repo", result.projectId)
            assertEquals("https://api.github.com", result.baseUrl)
            assertEquals("ghp_token", result.token)
            assertEquals(DetectionConfidence.HIGH, result.confidence)
            assertEquals("ci:github", result.source)
        }
    }

    @Test
    fun `detectFromCi GitHub falls back to GITHUB_REF when event payload is unavailable`() {
        runTest {
            val envMap = mapOf(
                "GITHUB_ACTIONS" to "true",
                "GITHUB_REPOSITORY" to "owner/repo",
                "GITHUB_REF" to "refs/pull/321/merge",
                "GITHUB_EVENT_PATH" to "/nonexistent/event.json"
            )
            val detector = PlatformDetector(providers, envProvider = envMap::get)

            val result = detector.detectFromCi()
            assertNotNull(result)
            assertEquals("321", result.mrId)
        }
    }

    @Test
    fun `detectFromCi detects GitLab CI`() {
        runTest {
            val envMap = mapOf(
                "GITLAB_CI" to "true",
                "CI_PROJECT_PATH" to "owner/repo",
                "CI_MERGE_REQUEST_IID" to "42",
                "CI_API_V4_URL" to "https://gitlab.example.com/api/v4",
                "CI_JOB_TOKEN" to "gitlab_token"
            )
            val detector = PlatformDetector(providers, envProvider = envMap::get)

            val result = detector.detectFromCi()
            assertNotNull(result)
            assertEquals("gitlab", result.platform)
            assertEquals("owner/repo", result.projectId)
            assertEquals("42", result.mrId)
            assertEquals("https://gitlab.example.com", result.baseUrl)
            assertEquals("gitlab_token", result.token)
            assertEquals(DetectionConfidence.HIGH, result.confidence)
            assertEquals("ci:gitlab", result.source)
        }
    }

    @Test
    fun `detectFromCi detects GitLab CI with default API URL`() {
        runTest {
            val envMap = mapOf(
                "GITLAB_CI" to "true",
                "CI_PROJECT_PATH" to "owner/repo"
            )
            val detector = PlatformDetector(providers, envProvider = envMap::get)

            val result = detector.detectFromCi()
            assertNotNull(result)
            assertEquals("gitlab", result.platform)
            assertEquals("https://gitlab.com", result.baseUrl)
        }
    }

    @Test
    fun `detectFromCi detects Bitbucket Pipelines`() {
        runTest {
            val envMap = mapOf(
                "BITBUCKET_BUILD_NUMBER" to "123",
                "BITBUCKET_REPO_FULL_NAME" to "owner/repo",
                "BITBUCKET_PR_ID" to "99"
            )
            val detector = PlatformDetector(providers, envProvider = envMap::get)

            val result = detector.detectFromCi()
            assertNotNull(result)
            assertEquals("bitbucket", result.platform)
            assertEquals("owner/repo", result.projectId)
            assertEquals("99", result.mrId)
            assertEquals("https://api.bitbucket.org/2.0", result.baseUrl)
            assertEquals(DetectionConfidence.HIGH, result.confidence)
            assertEquals("ci:bitbucket", result.source)
        }
    }

    @Test
    fun `detectFromCi resolves Jenkins CHANGE_URL via shared CI parser`() {
        runTest {
            val envMap = mapOf(
                "JENKINS_URL" to "https://jenkins.example.com",
                "CHANGE_URL" to "https://github.com/owner/repo/pull/77"
            )
            val detector = PlatformDetector(providers, envProvider = envMap::get)

            val result = detector.detectFromCi()
            assertNotNull(result)
            assertEquals("github", result.platform)
            assertEquals("owner/repo", result.projectId)
            assertEquals("77", result.mrId)
            assertEquals(DetectionConfidence.HIGH, result.confidence)
            assertEquals("ci:github", result.source)
        }
    }

    @Test
    fun `detectFromCi detects Azure Pipelines with AZURE_HTTP_USER_AGENT`() {
        runTest {
            val envMap = mapOf(
                "AZURE_HTTP_USER_AGENT" to "azure-pipelines",
                "BUILD_REPOSITORY_NAME" to "project/repo",
                "SYSTEM_PULLREQUEST_PULLREQUESTID" to "456",
                "SYSTEM_TEAMFOUNDATIONCOLLECTIONURI" to "https://dev.azure.com/org/",
                "SYSTEM_TEAMPROJECT" to "project",
                "SYSTEM_ACCESSTOKEN" to "azure_token"
            )
            val detector = PlatformDetector(providers, envProvider = envMap::get)

            val result = detector.detectFromCi()
            assertNotNull(result)
            assertEquals("azure-devops", result.platform)
            assertEquals("org/project/project/repo", result.projectId)
            assertEquals("456", result.mrId)
            assertEquals("https://dev.azure.com/org/project/_apis", result.baseUrl)
            assertEquals("azure_token", result.token)
            assertEquals(DetectionConfidence.HIGH, result.confidence)
            assertEquals("ci:azure-devops", result.source)
        }
    }

    @Test
    fun `detectFromCi detects Azure Pipelines with TF_BUILD`() {
        runTest {
            val envMap = mapOf(
                "TF_BUILD" to "True",
                "BUILD_REPOSITORY_NAME" to "project/repo"
            )
            val detector = PlatformDetector(providers, envProvider = envMap::get)

            val result = detector.detectFromCi()
            assertNotNull(result)
            assertEquals("azure-devops", result.platform)
            assertEquals("project/repo", result.projectId)
        }
    }

    @Test
    fun `detectFromCi returns null when no CI environment detected`() {
        runTest {
            val detector = PlatformDetector(providers, envProvider = { null })
            val result = detector.detectFromCi()
            assertNull(result)
        }
    }

    @Test
    fun `detectFromGitRemote detects GitHub from git remote`() {
        runTest {
            val detector = object : PlatformDetector(providers, envProvider = { null }) {
                override fun readGitRemote(): String = "https://github.com/owner/repo.git"
            }

            val result = detector.detectFromGitRemote()
            assertNotNull(result)
            assertEquals("github", result.platform)
            assertEquals("owner/repo", result.projectId)
            assertNull(result.mrId)
            assertEquals("https://api.github.com", result.baseUrl)
            assertNull(result.token)
            assertEquals(DetectionConfidence.MEDIUM, result.confidence)
            assertEquals("git-remote:github", result.source)
        }
    }

    @Test
    fun `detectFromGitRemote detects GitLab from git remote`() {
        runTest {
            val detector = object : PlatformDetector(providers, envProvider = { null }) {
                override fun readGitRemote(): String = "git@gitlab.com:owner/repo.git"
            }

            val result = detector.detectFromGitRemote()
            assertNotNull(result)
            assertEquals("gitlab", result.platform)
            assertEquals("owner/repo", result.projectId)
            assertEquals("https://gitlab.com", result.baseUrl)
            assertEquals(DetectionConfidence.MEDIUM, result.confidence)
            assertEquals("git-remote:gitlab", result.source)
        }
    }

    @Test
    fun `detectFromGitRemote detects Bitbucket from git remote`() {
        runTest {
            val detector = object : PlatformDetector(providers, envProvider = { null }) {
                override fun readGitRemote(): String = "https://bitbucket.org/owner/repo.git"
            }

            val result = detector.detectFromGitRemote()
            assertNotNull(result)
            assertEquals("bitbucket", result.platform)
            assertEquals("owner/repo", result.projectId)
            assertEquals("https://api.bitbucket.org/2.0", result.baseUrl)
            assertEquals(DetectionConfidence.MEDIUM, result.confidence)
            assertEquals("git-remote:bitbucket", result.source)
        }
    }

    @Test
    fun `detectFromGitRemote returns null when git command fails`() {
        runTest {
            val detector = object : PlatformDetector(providers, envProvider = { null }) {
                override fun readGitRemote(): String? = null
            }

            val result = detector.detectFromGitRemote()
            assertNull(result)
        }
    }

    @Test
    fun `detectFromGitRemote returns null when URL cannot be parsed`() {
        runTest {
            val detector = object : PlatformDetector(providers, envProvider = { null }) {
                override fun readGitRemote(): String = "invalid-url"
            }

            val result = detector.detectFromGitRemote()
            assertNull(result)
        }
    }

    @Test
    fun `detect prioritizes explicit env over CI`() {
        runTest {
            val envMap = mapOf(
                "KARANDASH_PLATFORM" to "github",
                "KARANDASH_PROJECT_ID" to "explicit/repo",
                "GITLAB_CI" to "true",
                "CI_PROJECT_PATH" to "ci/repo"
            )
            val detector = PlatformDetector(providers, envProvider = envMap::get)

            val result = detector.detect()
            assertNotNull(result)
            assertEquals("explicit/repo", result.projectId)
            assertEquals("explicit", result.source)
        }
    }

    @Test
    fun `detect prioritizes CI over git remote`() {
        runTest {
            val envMap = mapOf(
                "GITHUB_ACTIONS" to "true",
                "GITHUB_REPOSITORY" to "ci/repo"
            )
            val detector = object : PlatformDetector(providers, envProvider = envMap::get) {
                override fun readGitRemote(): String = "https://github.com/git/repo.git"
            }

            val result = detector.detect()
            assertNotNull(result)
            assertEquals("ci/repo", result.projectId)
            assertEquals("ci:github", result.source)
        }
    }

    @Test
    fun `detect falls back to git remote when no env or CI`() {
        runTest {
            val detector = object : PlatformDetector(providers, envProvider = { null }) {
                override fun readGitRemote(): String = "https://github.com/owner/repo.git"
            }

            val result = detector.detect()
            assertNotNull(result)
            assertEquals("owner/repo", result.projectId)
            assertEquals("git-remote:github", result.source)
        }
    }

    @Test
    fun `detect returns null when nothing detected`() {
        runTest {
            val detector = object : PlatformDetector(providers, envProvider = { null }) {
                override fun readGitRemote(): String? = null
            }

            val result = detector.detect()
            assertNull(result)
        }
    }

    @Test
    fun `readGitRemote returns null on error`() {
        val detector = PlatformDetector(
            envProvider = { null },
            workingDir = Path("/nonexistent/directory")
        )

        val result = detector.readGitRemote()
        assertNull(result)
    }
}
