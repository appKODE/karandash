package io.karandash.platforms.bitbucket

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BitbucketProviderTest {
    private val provider = BitbucketPlatformService()

    // --- parseMergeRequestUrl ---

    @Test
    fun parseMergeRequestUrl_standardBitbucketOrg_returnsCorrectComponents() {
        runTest {
            val result = provider.parseMergeRequestUrl(
                "https://bitbucket.org/myworkspace/myrepo/pull-requests/123"
            )

            assertNotNull(result)
            assertEquals("bitbucket", result.platform)
            assertEquals("myworkspace/myrepo", result.projectId)
            assertEquals("123", result.mrId)
        }
    }

    @Test
    fun parseMergeRequestUrl_hyphenatedWorkspaceAndRepo_parsesCorrectly() {
        runTest {
            val result = provider.parseMergeRequestUrl(
                "https://bitbucket.org/company-name/project-name/pull-requests/456"
            )

            assertNotNull(result)
            assertEquals("company-name/project-name", result.projectId)
            assertEquals("456", result.mrId)
        }
    }

    @Test
    fun parseMergeRequestUrl_apiBaseUrlIsSet() {
        runTest {
            val result = provider.parseMergeRequestUrl(
                "https://bitbucket.org/workspace/repo/pull-requests/1"
            )

            assertNotNull(result)
            assertEquals("https://api.bitbucket.org/2.0", result.baseUrl)
        }
    }

    @Test
    fun parseMergeRequestUrl_githubUrl_returnsNull() {
        runTest {
            val result = provider.parseMergeRequestUrl("https://github.com/owner/repo/pull/1")
            assertNull(result)
        }
    }

    @Test
    fun parseMergeRequestUrl_gitlabUrl_returnsNull() {
        runTest {
            val result = provider.parseMergeRequestUrl(
                "https://gitlab.com/group/project/-/merge_requests/1"
            )
            assertNull(result)
        }
    }

    @Test
    fun parseMergeRequestUrl_missingPullRequestsSegment_returnsNull() {
        runTest {
            val result = provider.parseMergeRequestUrl(
                "https://bitbucket.org/workspace/repo/123"
            )
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

    // --- parseGitRemoteUrl ---

    @Test
    fun parseGitRemoteUrl_sshFormat_returnsWorkspaceRepo() {
        runTest {
            val result = provider.parseGitRemoteUrl("git@bitbucket.org:workspace/repo.git")
            assertEquals("workspace/repo", result)
        }
    }

    @Test
    fun parseGitRemoteUrl_sshFormatWithoutDotGit_returnsWorkspaceRepo() {
        runTest {
            val result = provider.parseGitRemoteUrl("git@bitbucket.org:workspace/repo")
            assertEquals("workspace/repo", result)
        }
    }

    @Test
    fun parseGitRemoteUrl_httpsFormat_returnsWorkspaceRepo() {
        runTest {
            val result = provider.parseGitRemoteUrl("https://bitbucket.org/workspace/repo.git")
            assertEquals("workspace/repo", result)
        }
    }

    @Test
    fun parseGitRemoteUrl_httpsFormatWithoutDotGit_returnsWorkspaceRepo() {
        runTest {
            val result = provider.parseGitRemoteUrl("https://bitbucket.org/workspace/repo")
            assertEquals("workspace/repo", result)
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
    fun parseGitRemoteUrl_gitlabHttpsUrl_returnsNull() {
        runTest {
            val result = provider.parseGitRemoteUrl("https://gitlab.com/group/project.git")
            assertNull(result)
        }
    }

    // --- detectCiEnvironment ---

    @Test
    fun detectCiEnvironment_bitbucketBuildNumberSet_returnsEnvironment() {
        runTest {
            val env = mapOf(
                "BITBUCKET_BUILD_NUMBER" to "42",
                "BITBUCKET_REPO_FULL_NAME" to "workspace/repo",
                "BITBUCKET_PR_ID" to "7",
                "BITBUCKET_ACCESS_TOKEN" to "bb-token",
            )

            val result = provider.detectCiEnvironment { env[it] }

            assertNotNull(result)
            assertEquals("bitbucket", result.platform)
            assertEquals("workspace/repo", result.projectId)
            assertEquals("7", result.mrId)
            assertEquals("bb-token", result.token)
            assertEquals("https://api.bitbucket.org/2.0", result.baseUrl)
        }
    }

    @Test
    fun detectCiEnvironment_buildNumberNotSet_returnsNull() {
        runTest {
            val result = provider.detectCiEnvironment { null }
            assertNull(result)
        }
    }

    @Test
    fun detectCiEnvironment_missingOptionalVars_nullFieldsAreNull() {
        runTest {
            val env = mapOf("BITBUCKET_BUILD_NUMBER" to "1")

            val result = provider.detectCiEnvironment { env[it] }

            assertNotNull(result)
            assertNull(result.projectId)
            assertNull(result.mrId)
            assertNull(result.token)
        }
    }

    // --- provider metadata ---

    @Test
    fun providerId_isBitbucket() {
        assertEquals("bitbucket", provider.id)
    }

    @Test
    fun defaultApiUrl_isBitbucketApiOrg() {
        runTest {
            assertEquals("https://api.bitbucket.org/2.0", provider.defaultApiUrl())
        }
    }

    @Test
    fun authTokenHint_mentionsBitbucketToken() {
        runTest {
            val hint = provider.authTokenHint()
            assertNotNull(hint)
            assertTrue(
                hint.contains("BITBUCKET_TOKEN"),
                "Expected hint to mention BITBUCKET_TOKEN, got: $hint",
            )
        }
    }
}
