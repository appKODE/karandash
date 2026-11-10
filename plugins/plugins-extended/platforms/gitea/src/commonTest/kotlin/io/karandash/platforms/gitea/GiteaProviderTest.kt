package io.karandash.platforms.gitea

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GiteaProviderTest {
    private val provider = GiteaPlatformService()

    // --- parseMergeRequestUrl ---

    @Test
    fun parseMergeRequestUrl_standardGiteaUrl_returnsCorrectComponents() {
        runTest {
            val result = provider.parseMergeRequestUrl("https://gitea.example.com/owner/repo/pulls/42")

            assertNotNull(result)
            assertEquals("gitea", result.platform)
            assertEquals("https://gitea.example.com", result.baseUrl)
            assertEquals("owner/repo", result.projectId)
            assertEquals("42", result.mrId)
        }
    }

    @Test
    fun parseMergeRequestUrl_giteaComUrl_returnsCorrectComponents() {
        runTest {
            val result = provider.parseMergeRequestUrl("https://gitea.com/myorg/myrepo/pulls/7")

            assertNotNull(result)
            assertEquals("https://gitea.com", result.baseUrl)
            assertEquals("myorg/myrepo", result.projectId)
            assertEquals("7", result.mrId)
        }
    }

    @Test
    fun parseMergeRequestUrl_selfHostedUrl_returnsCorrectComponents() {
        runTest {
            val result = provider.parseMergeRequestUrl("https://code.mycompany.io/team/service/pulls/100")

            assertNotNull(result)
            assertEquals("https://code.mycompany.io", result.baseUrl)
            assertEquals("team/service", result.projectId)
            assertEquals("100", result.mrId)
        }
    }

    @Test
    fun parseMergeRequestUrl_githubPullUrl_returnsNull() {
        runTest {
            val result = provider.parseMergeRequestUrl("https://github.com/owner/repo/pulls/42")
            assertNull(result)
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
            val result = provider.parseMergeRequestUrl("https://gitea.example.com/owner/repo/issues/5")
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
            val result = provider.parseMergeRequestUrl("https://gitea.example.com")
            assertNull(result)
        }
    }

    // --- parseGitRemoteUrl ---

    @Test
    fun parseGitRemoteUrl_sshFormat_returnsOwnerRepo() {
        runTest {
            val result = provider.parseGitRemoteUrl("git@gitea.example.com:owner/repo.git")
            assertEquals("owner/repo", result)
        }
    }

    @Test
    fun parseGitRemoteUrl_sshFormatWithoutDotGit_returnsOwnerRepo() {
        runTest {
            val result = provider.parseGitRemoteUrl("git@gitea.example.com:owner/repo")
            assertEquals("owner/repo", result)
        }
    }

    @Test
    fun parseGitRemoteUrl_httpsFormat_returnsOwnerRepo() {
        runTest {
            val result = provider.parseGitRemoteUrl("https://gitea.example.com/owner/repo.git")
            assertEquals("owner/repo", result)
        }
    }

    @Test
    fun parseGitRemoteUrl_httpsFormatWithoutDotGit_returnsOwnerRepo() {
        runTest {
            val result = provider.parseGitRemoteUrl("https://gitea.example.com/owner/repo")
            assertEquals("owner/repo", result)
        }
    }

    @Test
    fun parseGitRemoteUrl_githubSshUrl_returnsNull() {
        runTest {
            val result = provider.parseGitRemoteUrl("git@github.com:owner/repo.git")
            assertNull(result)
        }
    }

    @Test
    fun parseGitRemoteUrl_gitlabSshUrl_returnsNull() {
        runTest {
            val result = provider.parseGitRemoteUrl("git@gitlab.com:group/project.git")
            assertNull(result)
        }
    }

    @Test
    fun parseGitRemoteUrl_bitbucketSshUrl_returnsNull() {
        runTest {
            val result = provider.parseGitRemoteUrl("git@bitbucket.org:team/repo.git")
            assertNull(result)
        }
    }

    @Test
    fun parseGitRemoteUrl_githubHttpsUrl_returnsNull() {
        runTest {
            val result = provider.parseGitRemoteUrl("https://github.com/owner/repo.git")
            assertNull(result)
        }
    }

    // --- detectCiEnvironment ---

    @Test
    fun detectCiEnvironment_allGiteaVarsPresent_returnsEnvironment() {
        runTest {
            val env = mapOf(
                "GITEA_SERVER_URL" to "https://gitea.example.com",
                "GITEA_REPO_OWNER" to "myorg",
                "GITEA_REPO_NAME" to "myrepo",
                "GITEA_PR_ID" to "42",
                "GITEA_TOKEN" to "gitea-token",
            )

            val result = provider.detectCiEnvironment { env[it] }

            assertNotNull(result)
            assertEquals("gitea", result.platform)
            assertEquals("myorg/myrepo", result.projectId)
            assertEquals("42", result.mrId)
            assertEquals("gitea-token", result.token)
            assertEquals("https://gitea.example.com", result.baseUrl)
        }
    }

    @Test
    fun detectCiEnvironment_serverUrlNotSet_returnsNull() {
        runTest {
            val result = provider.detectCiEnvironment { null }
            assertNull(result)
        }
    }

    @Test
    fun detectCiEnvironment_onlyServerUrl_returnsEnvironmentWithNulls() {
        runTest {
            val env = mapOf("GITEA_SERVER_URL" to "https://gitea.example.com")

            val result = provider.detectCiEnvironment { env[it] }

            assertNotNull(result)
            assertEquals("gitea", result.platform)
            assertEquals("https://gitea.example.com", result.baseUrl)
            assertNull(result.projectId)
            assertNull(result.mrId)
            assertNull(result.token)
        }
    }

    @Test
    fun detectCiEnvironment_ownerWithoutRepoName_projectIdIsNull() {
        runTest {
            val env = mapOf(
                "GITEA_SERVER_URL" to "https://gitea.example.com",
                "GITEA_REPO_OWNER" to "myorg",
            )

            val result = provider.detectCiEnvironment { env[it] }

            assertNotNull(result)
            assertNull(result.projectId)
        }
    }

    @Test
    fun detectCiEnvironment_repoNameWithoutOwner_projectIdIsNull() {
        runTest {
            val env = mapOf(
                "GITEA_SERVER_URL" to "https://gitea.example.com",
                "GITEA_REPO_NAME" to "myrepo",
            )

            val result = provider.detectCiEnvironment { env[it] }

            assertNotNull(result)
            assertNull(result.projectId)
        }
    }

    @Test
    fun detectCiEnvironment_ownerAndRepoPresent_projectIdIsCombined() {
        runTest {
            val env = mapOf(
                "GITEA_SERVER_URL" to "https://gitea.example.com",
                "GITEA_REPO_OWNER" to "team",
                "GITEA_REPO_NAME" to "service",
            )

            val result = provider.detectCiEnvironment { env[it] }

            assertNotNull(result)
            assertEquals("team/service", result.projectId)
        }
    }

    // --- provider metadata ---

    @Test
    fun providerId_isGitea() {
        assertEquals("gitea", provider.id)
    }

    @Test
    fun defaultApiUrl_isNullForSelfHostedProvider() {
        runTest {
            assertNull(provider.defaultApiUrl())
        }
    }

    @Test
    fun authTokenHint_mentionsGiteaToken() {
        runTest {
            val hint = provider.authTokenHint()
            assertNotNull(hint)
            assertTrue(
                hint.contains("GITEA_TOKEN"),
                "Expected hint to mention GITEA_TOKEN, got: $hint",
            )
        }
    }
}
