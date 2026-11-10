package io.karandash.platforms.gerrit

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GerritProviderTest {
    private val provider = GerritPlatformService()

    // --- parseMergeRequestUrl ---

    @Test
    fun parseMergeRequestUrl_modernFormat_returnsCorrectComponents() {
        runTest {
            val result = provider.parseMergeRequestUrl("https://gerrit.example.com/c/my-project/+/12345")

            assertNotNull(result)
            assertEquals("gerrit", result.platform)
            assertEquals("https://gerrit.example.com", result.baseUrl)
            assertEquals("my-project", result.projectId)
            assertEquals("12345", result.mrId)
        }
    }

    @Test
    fun parseMergeRequestUrl_withPatchset_returnsChangeId() {
        runTest {
            val result = provider.parseMergeRequestUrl("https://gerrit.example.com/c/my-project/+/12345/3")

            assertNotNull(result)
            assertEquals("my-project", result.projectId)
            assertEquals("12345", result.mrId)
        }
    }

    @Test
    fun parseMergeRequestUrl_nestedProject_returnsFullProjectPath() {
        runTest {
            val result = provider.parseMergeRequestUrl("https://gerrit.example.com/c/org/sub-project/+/99")

            assertNotNull(result)
            assertEquals("org/sub-project", result.projectId)
            assertEquals("99", result.mrId)
        }
    }

    @Test
    fun parseMergeRequestUrl_githubUrl_returnsNull() {
        runTest {
            val result = provider.parseMergeRequestUrl("https://github.com/owner/repo/pull/42")
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
    fun parseMergeRequestUrl_legacyHashFormat_returnsNull() {
        runTest {
            val result = provider.parseMergeRequestUrl("https://gerrit.example.com/#12345")
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
            val result = provider.parseMergeRequestUrl("https://gerrit.example.com")
            assertNull(result)
        }
    }

    // --- parseGitRemoteUrl ---

    @Test
    fun parseGitRemoteUrl_sshFormatWithGerritHost_returnsProject() {
        runTest {
            val result = provider.parseGitRemoteUrl("ssh://user@gerrit.example.com:29418/my-project.git")
            assertEquals("my-project", result)
        }
    }

    @Test
    fun parseGitRemoteUrl_sshFormatWithoutDotGit_returnsProject() {
        runTest {
            val result = provider.parseGitRemoteUrl("ssh://user@gerrit.example.com:29418/my-project")
            assertEquals("my-project", result)
        }
    }

    @Test
    fun parseGitRemoteUrl_sshFormatNoPort_returnsProject() {
        runTest {
            val result = provider.parseGitRemoteUrl("ssh://user@gerrit.example.com/my-project.git")
            assertEquals("my-project", result)
        }
    }

    @Test
    fun parseGitRemoteUrl_httpsFormatWithGerritHost_returnsProject() {
        runTest {
            val result = provider.parseGitRemoteUrl("https://gerrit.example.com/my-project.git")
            assertEquals("my-project", result)
        }
    }

    @Test
    fun parseGitRemoteUrl_httpsFormatWithAuthPrefix_returnsProject() {
        runTest {
            val result = provider.parseGitRemoteUrl("https://gerrit.example.com/a/my-project.git")
            assertEquals("my-project", result)
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

    // --- detectCiEnvironment ---

    @Test
    fun detectCiEnvironment_allGerritVarsPresent_returnsEnvironment() {
        runTest {
            val env = mapOf(
                "GERRIT_URL" to "https://gerrit.example.com",
                "GERRIT_PROJECT" to "my-project",
                "GERRIT_CHANGE_NUMBER" to "12345",
                "GERRIT_TOKEN" to "secret-token",
            )

            val result = provider.detectCiEnvironment { env[it] }

            assertNotNull(result)
            assertEquals("gerrit", result.platform)
            assertEquals("my-project", result.projectId)
            assertEquals("12345", result.mrId)
            assertEquals("secret-token", result.token)
            assertEquals("https://gerrit.example.com", result.baseUrl)
        }
    }

    @Test
    fun detectCiEnvironment_gerritUrlNotSet_returnsNull() {
        runTest {
            val result = provider.detectCiEnvironment { null }
            assertNull(result)
        }
    }

    @Test
    fun detectCiEnvironment_onlyGerritUrl_returnsEnvironmentWithNulls() {
        runTest {
            val env = mapOf("GERRIT_URL" to "https://gerrit.example.com")

            val result = provider.detectCiEnvironment { env[it] }

            assertNotNull(result)
            assertEquals("gerrit", result.platform)
            assertEquals("https://gerrit.example.com", result.baseUrl)
            assertNull(result.projectId)
            assertNull(result.mrId)
            assertNull(result.token)
        }
    }

    @Test
    fun detectCiEnvironment_missingChangeNumber_mrIdIsNull() {
        runTest {
            val env = mapOf(
                "GERRIT_URL" to "https://gerrit.example.com",
                "GERRIT_PROJECT" to "my-project",
            )

            val result = provider.detectCiEnvironment { env[it] }

            assertNotNull(result)
            assertNull(result.mrId)
        }
    }

    // --- provider metadata ---

    @Test
    fun providerId_isGerrit() {
        assertEquals("gerrit", provider.id)
    }

    @Test
    fun defaultApiUrl_isNullForSelfHostedProvider() {
        runTest {
            assertNull(provider.defaultApiUrl())
        }
    }

    @Test
    fun authTokenHint_mentionsGerritToken() {
        runTest {
            val hint = provider.authTokenHint()
            assertNotNull(hint)
            assertTrue(
                hint.contains("GERRIT_TOKEN"),
                "Expected hint to mention GERRIT_TOKEN, got: $hint",
            )
        }
    }
}
