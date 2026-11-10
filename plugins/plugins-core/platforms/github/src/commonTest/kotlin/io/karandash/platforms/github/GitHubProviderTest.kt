package io.karandash.platforms.github

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubProviderTest {
    private val provider = GitHubPlatformService()

    // --- parseMergeRequestUrl ---

    @Test
    fun parseMergeRequestUrl_standardGitHubCom_returnsCorrectComponents() {
        runTest {
            val result = provider.parseMergeRequestUrl("https://github.com/owner/repo/pull/42")

            assertNotNull(result)
            assertEquals("github", result.platform)
            assertEquals("https://api.github.com", result.baseUrl)
            assertEquals("owner/repo", result.projectId)
            assertEquals("42", result.mrId)
        }
    }

    @Test
    fun parseMergeRequestUrl_gitHubEnterpriseUrl_returnsEnterpriseApiBase() {
        runTest {
            val result = provider.parseMergeRequestUrl("https://github.example.com/owner/repo/pull/7")

            assertNotNull(result)
            assertEquals("https://github.example.com/api/v3", result.baseUrl)
            assertEquals("owner/repo", result.projectId)
            assertEquals("7", result.mrId)
        }
    }

    @Test
    fun parseMergeRequestUrl_gitlabUrl_returnsNull() {
        runTest {
            val result = provider.parseMergeRequestUrl("https://gitlab.com/group/project/-/merge_requests/1")
            assertNull(result)
        }
    }

    @Test
    fun parseMergeRequestUrl_issueUrl_returnsNull() {
        runTest {
            val result = provider.parseMergeRequestUrl("https://github.com/owner/repo/issues/5")
            assertNull(result)
        }
    }

    @Test
    fun parseMergeRequestUrl_missingPullSegment_returnsNull() {
        runTest {
            val result = provider.parseMergeRequestUrl("https://github.com/owner/repo/42")
            assertNull(result)
        }
    }

    @Test
    fun parseMergeRequestUrl_emptyString_returnsNull() {
        runTest {
            val result = provider.parseMergeRequestUrl("")
            assertNull(result)
        }
    }

    @Test
    fun parseMergeRequestUrl_onlyDomain_returnsNull() {
        runTest {
            val result = provider.parseMergeRequestUrl("https://github.com")
            assertNull(result)
        }
    }

    // --- parseGitRemoteUrl ---

    @Test
    fun parseGitRemoteUrl_sshFormat_returnsOwnerRepo() {
        runTest {
            val result = provider.parseGitRemoteUrl("git@github.com:owner/repo.git")
            assertEquals("owner/repo", result)
        }
    }

    @Test
    fun parseGitRemoteUrl_sshFormatWithoutDotGit_returnsOwnerRepo() {
        runTest {
            val result = provider.parseGitRemoteUrl("git@github.com:owner/repo")
            assertEquals("owner/repo", result)
        }
    }

    @Test
    fun parseGitRemoteUrl_httpsFormat_returnsOwnerRepo() {
        runTest {
            val result = provider.parseGitRemoteUrl("https://github.com/owner/repo.git")
            assertEquals("owner/repo", result)
        }
    }

    @Test
    fun parseGitRemoteUrl_httpsFormatWithoutDotGit_returnsOwnerRepo() {
        runTest {
            val result = provider.parseGitRemoteUrl("https://github.com/owner/repo")
            assertEquals("owner/repo", result)
        }
    }

    @Test
    fun parseGitRemoteUrl_gitlabSshUrl_returnsNull() {
        runTest {
            val result = provider.parseGitRemoteUrl("git@gitlab.com:group/project.git")
            assertNull(result)
        }
    }

    // --- detectCiEnvironment ---

    @Test
    fun detectCiEnvironment_githubActionsTrue_returnsEnvironment() {
        runTest {
            val env = mapOf(
                "GITHUB_ACTIONS" to "true",
                "GITHUB_REPOSITORY" to "owner/repo",
                "GITHUB_REF" to "refs/pull/99/merge",
                "GITHUB_TOKEN" to "ghp_test",
                "GITHUB_API_URL" to "https://api.github.com",
            )

            val result = provider.detectCiEnvironment { env[it] }

            assertNotNull(result)
            assertEquals("github", result.platform)
            assertEquals("owner/repo", result.projectId)
            assertEquals("99", result.mrId)
            assertEquals("ghp_test", result.token)
            assertEquals("https://api.github.com", result.baseUrl)
        }
    }

    @Test
    fun detectCiEnvironment_githubActionsNotSet_returnsNull() {
        runTest {
            val result = provider.detectCiEnvironment { null }
            assertNull(result)
        }
    }

    @Test
    fun detectCiEnvironment_githubActionsFalse_returnsNull() {
        runTest {
            val result = provider.detectCiEnvironment { key ->
                if (key == "GITHUB_ACTIONS") "false" else null
            }
            assertNull(result)
        }
    }

    @Test
    fun detectCiEnvironment_nonPrRef_mrIdIsNull() {
        runTest {
            val env = mapOf(
                "GITHUB_ACTIONS" to "true",
                "GITHUB_REF" to "refs/heads/main",
            )

            val result = provider.detectCiEnvironment { env[it] }

            assertNotNull(result)
            assertNull(result.mrId)
        }
    }

    @Test
    fun detectCiEnvironment_noApiUrlEnvVar_usesDefaultApiUrl() {
        runTest {
            val env = mapOf("GITHUB_ACTIONS" to "true")

            val result = provider.detectCiEnvironment { env[it] }

            assertNotNull(result)
            assertEquals("https://api.github.com", result.baseUrl)
        }
    }

    // --- provider metadata ---

    @Test
    fun providerId_isGithub() {
        assertEquals("github", provider.id)
    }

    @Test
    fun defaultApiUrl_isGitHubApiCom() {
        runTest {
            assertEquals("https://api.github.com", provider.defaultApiUrl())
        }
    }

    @Test
    fun authTokenHint_mentionsGithubToken() {
        runTest {
            val hint = provider.authTokenHint()
            assertNotNull(hint)
            assertTrue(
                hint.contains("GITHUB_TOKEN"),
                "Expected hint to mention GITHUB_TOKEN, got: $hint",
            )
        }
    }
}
