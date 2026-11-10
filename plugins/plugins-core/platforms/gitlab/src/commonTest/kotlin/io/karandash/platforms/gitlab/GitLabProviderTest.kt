package io.karandash.platforms.gitlab

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitLabProviderTest {
    private val provider = GitLabPlatformService()

    // --- parseMergeRequestUrl ---

    @Test
    fun parseMergeRequestUrl_standardGitLabCom_returnsCorrectComponents() {
        runTest {
            val result = provider.parseMergeRequestUrl("https://gitlab.com/group/project/-/merge_requests/123")

            assertNotNull(result)
            assertEquals("gitlab", result.platform)
            assertEquals("https://gitlab.com", result.baseUrl)
            assertEquals("group/project", result.projectId)
            assertEquals("123", result.mrId)
        }
    }

    @Test
    fun parseMergeRequestUrl_selfHostedInstance_returnsCorrectBaseUrl() {
        runTest {
            val result = provider.parseMergeRequestUrl(
                "https://gitlab.example.com/group/project/-/merge_requests/456"
            )

            assertNotNull(result)
            assertEquals("https://gitlab.example.com", result.baseUrl)
            assertEquals("group/project", result.projectId)
            assertEquals("456", result.mrId)
        }
    }

    @Test
    fun parseMergeRequestUrl_nestedGroups_capturesFullProjectPath() {
        runTest {
            val result = provider.parseMergeRequestUrl(
                "https://gitlab.com/group/subgroup/project/-/merge_requests/789"
            )

            assertNotNull(result)
            assertEquals("group/subgroup/project", result.projectId)
            assertEquals("789", result.mrId)
        }
    }

    @Test
    fun parseMergeRequestUrl_deeplyNestedGroups_capturesFullProjectPath() {
        runTest {
            val result = provider.parseMergeRequestUrl(
                "https://gitlab.com/a/b/c/d/-/merge_requests/1"
            )

            assertNotNull(result)
            assertEquals("a/b/c/d", result.projectId)
            assertEquals("1", result.mrId)
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
    fun parseMergeRequestUrl_missingMergeRequestsSegment_returnsNull() {
        runTest {
            val result = provider.parseMergeRequestUrl("https://gitlab.com/group/project/issues/123")
            assertNull(result)
        }
    }

    @Test
    fun parseMergeRequestUrl_plainDomain_returnsNull() {
        runTest {
            val result = provider.parseMergeRequestUrl("https://gitlab.com")
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
    fun parseGitRemoteUrl_sshFormat_returnsProjectPath() {
        runTest {
            val result = provider.parseGitRemoteUrl("git@gitlab.com:group/project.git")
            assertEquals("group/project", result)
        }
    }

    @Test
    fun parseGitRemoteUrl_sshFormatWithoutDotGit_returnsProjectPath() {
        runTest {
            val result = provider.parseGitRemoteUrl("git@gitlab.com:group/project")
            assertEquals("group/project", result)
        }
    }

    @Test
    fun parseGitRemoteUrl_httpsFormat_returnsProjectPath() {
        runTest {
            val result = provider.parseGitRemoteUrl("https://gitlab.com/group/project.git")
            assertEquals("group/project", result)
        }
    }

    @Test
    fun parseGitRemoteUrl_httpsFormatWithoutDotGit_returnsProjectPath() {
        runTest {
            val result = provider.parseGitRemoteUrl("https://gitlab.com/group/project")
            assertEquals("group/project", result)
        }
    }

    @Test
    fun parseGitRemoteUrl_selfHostedHttps_returnsProjectPath() {
        runTest {
            val result = provider.parseGitRemoteUrl("https://gitlab.example.com/group/project.git")
            assertEquals("group/project", result)
        }
    }

    @Test
    fun parseGitRemoteUrl_githubSshUrl_returnsNull() {
        runTest {
            val result = provider.parseGitRemoteUrl("git@github.com:owner/repo.git")
            assertNull(result)
        }
    }

    // --- detectCiEnvironment ---

    @Test
    fun detectCiEnvironment_gitlabCiTrue_returnsEnvironment() {
        runTest {
            val env = mapOf(
                "GITLAB_CI" to "true",
                "CI_PROJECT_ID" to "2821",
                "CI_PROJECT_PATH" to "group/project",
                "CI_MERGE_REQUEST_IID" to "42",
                "CI_JOB_TOKEN" to "test-token",
                "CI_SERVER_URL" to "https://gitlab.com",
            )

            val result = provider.detectCiEnvironment { env[it] }

            assertNotNull(result)
            assertEquals("gitlab", result.platform)
            assertEquals("2821", result.projectId)
            assertEquals("42", result.mrId)
            assertEquals("test-token", result.token)
            assertEquals("https://gitlab.com", result.baseUrl)
        }
    }

    @Test
    fun detectCiEnvironment_gitlabCiNotSet_returnsNull() {
        runTest {
            val result = provider.detectCiEnvironment { null }
            assertNull(result)
        }
    }

    @Test
    fun detectCiEnvironment_gitlabCiFalse_returnsNull() {
        runTest {
            val result = provider.detectCiEnvironment { key -> if (key == "GITLAB_CI") "false" else null }
            assertNull(result)
        }
    }

    @Test
    fun detectCiEnvironment_ciApiV4UrlFallback_stripsSuffix() {
        runTest {
            val env = mapOf(
                "GITLAB_CI" to "true",
                "CI_API_V4_URL" to "https://gitlab.com/api/v4",
            )

            val result = provider.detectCiEnvironment { env[it] }

            assertNotNull(result)
            assertEquals("https://gitlab.com", result.baseUrl)
        }
    }

    @Test
    fun detectCiEnvironment_noServerUrl_usesDefaultBaseUrl() {
        runTest {
            val env = mapOf("GITLAB_CI" to "true")

            val result = provider.detectCiEnvironment { env[it] }

            assertNotNull(result)
            assertEquals("https://gitlab.com", result.baseUrl)
        }
    }

    @Test
    fun detectCiEnvironment_ciProjectIdFallback_usedWhenPathAbsent() {
        runTest {
            val env = mapOf(
                "GITLAB_CI" to "true",
                "CI_PROJECT_ID" to "12345",
            )

            val result = provider.detectCiEnvironment { env[it] }

            assertNotNull(result)
            assertEquals("12345", result.projectId)
        }
    }

    @Test
    fun detectCiEnvironment_ciProjectPathFallback_usedWhenIdBlank() {
        runTest {
            val env = mapOf(
                "GITLAB_CI" to "true",
                "CI_PROJECT_ID" to "",
                "CI_PROJECT_PATH" to "group/project",
            )

            val result = provider.detectCiEnvironment { env[it] }

            assertNotNull(result)
            assertEquals("group/project", result.projectId)
        }
    }

    // --- provider metadata ---

    @Test
    fun providerId_isGitlab() {
        assertEquals("gitlab", provider.id)
    }

    @Test
    fun defaultApiUrl_isGitLabCom() {
        runTest {
            assertEquals("https://gitlab.com", provider.defaultApiUrl())
        }
    }

    @Test
    fun authTokenHint_mentionsGitlabToken() {
        runTest {
            val hint = provider.authTokenHint()
            assertNotNull(hint)
            assertTrue(
                hint.contains("GITLAB_TOKEN") || hint.contains("CI_JOB_TOKEN"),
                "Expected hint to mention GITLAB_TOKEN or CI_JOB_TOKEN, got: $hint",
            )
        }
    }
}
