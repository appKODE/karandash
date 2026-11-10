package io.karandash.platforms.jenkins

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.models.support.KarandashJson
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JenkinsProviderTest {
    private val provider = JenkinsProvider(ModuleRegistryImpl())

    // -- Module metadata --

    @Test
    fun testModuleId() {
        assertEquals("jenkins", provider.id)
    }

    @Test
    fun testModuleName() {
        assertEquals("Jenkins CI", provider.name)
    }

    @Test
    fun testModuleType() {
        assertEquals(ModuleType.PLATFORM_SERVICE, provider.type)
    }

    @Test
    fun testPriority() {
        assertEquals(80, provider.defaultPriority)
    }

    // -- CI environment detection --

    @Test
    fun testDetectCiEnvironmentWithGitLabChangeUrl() {
        runTest {
            val env = mapOf(
                "JENKINS_URL" to "https://jenkins.example.com/",
                "CHANGE_URL" to "https://gitlab.com/group/project/-/merge_requests/123"
            )

            val ciEnv = provider.detectCiEnvironment { env[it] }

            assertNotNull(ciEnv)
            assertEquals("gitlab", ciEnv.platform)
            assertEquals("group/project", ciEnv.projectId)
            assertEquals("123", ciEnv.mrId)
            assertEquals("https://gitlab.com", ciEnv.baseUrl)
        }
    }

    @Test
    fun testDetectCiEnvironmentWithGitHubChangeUrl() {
        runTest {
            val env = mapOf(
                "JENKINS_URL" to "https://jenkins.example.com/",
                "CHANGE_URL" to "https://github.com/org/repo/pull/456"
            )

            val ciEnv = provider.detectCiEnvironment { env[it] }

            assertNotNull(ciEnv)
            assertEquals("github", ciEnv.platform)
            assertEquals("org/repo", ciEnv.projectId)
            assertEquals("456", ciEnv.mrId)
            assertEquals("https://api.github.com", ciEnv.baseUrl)
        }
    }

    @Test
    fun testDetectCiEnvironmentWithBitbucketChangeUrl() {
        runTest {
            val env = mapOf(
                "JENKINS_URL" to "https://jenkins.example.com/",
                "CHANGE_URL" to "https://bitbucket.org/team/repo/pull-requests/789"
            )

            val ciEnv = provider.detectCiEnvironment { env[it] }

            assertNotNull(ciEnv)
            assertEquals("bitbucket", ciEnv.platform)
            assertEquals("team/repo", ciEnv.projectId)
            assertEquals("789", ciEnv.mrId)
            assertEquals("https://api.bitbucket.org/2.0", ciEnv.baseUrl)
        }
    }

    @Test
    fun testDetectCiEnvironmentWithGerritChangeUrl() {
        runTest {
            val env = mapOf(
                "JENKINS_URL" to "https://jenkins.example.com/",
                "CHANGE_URL" to "https://gerrit.example.com/c/project-name/+/123"
            )

            val ciEnv = provider.detectCiEnvironment { env[it] }

            assertNotNull(ciEnv)
            assertEquals("gerrit", ciEnv.platform)
            assertEquals("project-name", ciEnv.projectId)
            assertEquals("123", ciEnv.mrId)
            assertEquals("https://gerrit.example.com", ciEnv.baseUrl)
        }
    }

    @Test
    fun testDetectCiEnvironmentWithAzureDevOpsChangeUrl() {
        runTest {
            val env = mapOf(
                "JENKINS_URL" to "https://jenkins.example.com/",
                "CHANGE_URL" to "https://dev.azure.com/org/project/_git/repo/pullrequest/123"
            )

            val ciEnv = provider.detectCiEnvironment { env[it] }

            assertNotNull(ciEnv)
            assertEquals("azure-devops", ciEnv.platform)
            assertEquals("org/project/repo", ciEnv.projectId)
            assertEquals("123", ciEnv.mrId)
            assertEquals("https://dev.azure.com", ciEnv.baseUrl)
        }
    }

    @Test
    fun testDetectCiEnvironmentWithGiteaChangeUrl() {
        runTest {
            val env = mapOf(
                "JENKINS_URL" to "https://jenkins.example.com/",
                "CHANGE_URL" to "https://gitea.example.com/owner/repo/pulls/123"
            )

            val ciEnv = provider.detectCiEnvironment { env[it] }

            assertNotNull(ciEnv)
            assertEquals("gitea", ciEnv.platform)
            assertEquals("owner/repo", ciEnv.projectId)
            assertEquals("123", ciEnv.mrId)
            assertEquals("https://gitea.example.com", ciEnv.baseUrl)
        }
    }

    @Test
    fun testDetectCiEnvironmentNotInJenkins() {
        runTest {
            val env = emptyMap<String, String>()

            val ciEnv = provider.detectCiEnvironment { env[it] }

            assertNull(ciEnv)
        }
    }

    @Test
    fun testDetectCiEnvironmentMissingChangeUrl() {
        runTest {
            val env = mapOf(
                "JENKINS_URL" to "https://jenkins.example.com/"
            )

            val ciEnv = provider.detectCiEnvironment { env[it] }

            assertNull(ciEnv)
        }
    }

    @Test
    fun testDetectCiEnvironmentUnknownChangeUrlFormat() {
        runTest {
            val env = mapOf(
                "JENKINS_URL" to "https://jenkins.example.com/",
                "CHANGE_URL" to "https://unknown-platform.com/some/path"
            )

            val ciEnv = provider.detectCiEnvironment { env[it] }

            assertNull(ciEnv)
        }
    }

    // -- CHANGE_URL parsing --

    @Test
    fun testParseGitLabChangeUrl() {
        val result = JenkinsProvider.parseChangeUrl(
            "https://gitlab.com/group/project/-/merge_requests/123"
        )

        assertNotNull(result)
        assertEquals("gitlab", result.platform)
        assertEquals("group/project", result.projectId)
        assertEquals("123", result.mrId)
        assertEquals("https://gitlab.com", result.baseUrl)
    }

    @Test
    fun testParseGitLabSelfHostedChangeUrl() {
        val result = JenkinsProvider.parseChangeUrl(
            "https://gitlab.example.com/group/subgroup/project/-/merge_requests/42"
        )

        assertNotNull(result)
        assertEquals("gitlab", result.platform)
        assertEquals("group/subgroup/project", result.projectId)
        assertEquals("42", result.mrId)
        assertEquals("https://gitlab.example.com", result.baseUrl)
    }

    @Test
    fun testParseGitHubChangeUrl() {
        val result = JenkinsProvider.parseChangeUrl(
            "https://github.com/org/repo/pull/456"
        )

        assertNotNull(result)
        assertEquals("github", result.platform)
        assertEquals("org/repo", result.projectId)
        assertEquals("456", result.mrId)
        assertEquals("https://api.github.com", result.baseUrl)
    }

    @Test
    fun testParseGitHubEnterpriseChangeUrl() {
        val result = JenkinsProvider.parseChangeUrl(
            "https://github.example.com/org/repo/pull/456"
        )

        assertNotNull(result)
        assertEquals("github", result.platform)
        assertEquals("org/repo", result.projectId)
        assertEquals("456", result.mrId)
        assertEquals("https://github.example.com/api/v3", result.baseUrl)
    }

    @Test
    fun testParseBitbucketChangeUrl() {
        val result = JenkinsProvider.parseChangeUrl(
            "https://bitbucket.org/team/repo/pull-requests/789"
        )

        assertNotNull(result)
        assertEquals("bitbucket", result.platform)
        assertEquals("team/repo", result.projectId)
        assertEquals("789", result.mrId)
        assertEquals("https://api.bitbucket.org/2.0", result.baseUrl)
    }

    @Test
    fun testParseGerritChangeUrl() {
        val result = JenkinsProvider.parseChangeUrl(
            "https://gerrit.example.com/c/project-name/+/123"
        )

        assertNotNull(result)
        assertEquals("gerrit", result.platform)
        assertEquals("project-name", result.projectId)
        assertEquals("123", result.mrId)
        assertEquals("https://gerrit.example.com", result.baseUrl)
    }

    @Test
    fun testParseAzureDevOpsCloudChangeUrl() {
        val result = JenkinsProvider.parseChangeUrl(
            "https://dev.azure.com/org/project/_git/repo/pullrequest/123"
        )

        assertNotNull(result)
        assertEquals("azure-devops", result.platform)
        assertEquals("org/project/repo", result.projectId)
        assertEquals("123", result.mrId)
        assertEquals("https://dev.azure.com", result.baseUrl)
    }

    @Test
    fun testParseAzureDevOpsSelfHostedChangeUrl() {
        val result = JenkinsProvider.parseChangeUrl(
            "https://azure.company.com/org/project/_git/repo/pullrequest/456"
        )

        assertNotNull(result)
        assertEquals("azure-devops", result.platform)
        assertEquals("org/project/repo", result.projectId)
        assertEquals("456", result.mrId)
        assertEquals("https://azure.company.com", result.baseUrl)
    }

    @Test
    fun testParseGiteaChangeUrl() {
        val result = JenkinsProvider.parseChangeUrl(
            "https://gitea.example.com/owner/repo/pulls/123"
        )

        assertNotNull(result)
        assertEquals("gitea", result.platform)
        assertEquals("owner/repo", result.projectId)
        assertEquals("123", result.mrId)
        assertEquals("https://gitea.example.com", result.baseUrl)
    }

    @Test
    fun testParseAwsCodeCommitChangeUrl() {
        val result = JenkinsProvider.parseChangeUrl(
            "https://console.aws.amazon.com/codesuite/codecommit/repositories/my-repo/pull-requests/42/details"
        )

        assertNotNull(result)
        assertEquals("awscodecommit", result.platform)
        assertEquals("my-repo", result.projectId)
        assertEquals("42", result.mrId)
        assertEquals("https://codecommit.amazonaws.com", result.baseUrl)
    }

    @Test
    fun testParseAwsCodeCommitRegionalChangeUrl() {
        val result = JenkinsProvider.parseChangeUrl(
            "https://us-east-1.console.aws.amazon.com/codesuite/codecommit/repositories/my-repo/pull-requests/7/details"
        )

        assertNotNull(result)
        assertEquals("awscodecommit", result.platform)
        assertEquals("my-repo", result.projectId)
        assertEquals("7", result.mrId)
        assertEquals("https://codecommit.amazonaws.com", result.baseUrl)
    }

    @Test
    fun testDetectCiEnvironmentWithAwsCodeCommitChangeUrl() {
        runTest {
            val env = mapOf(
                "JENKINS_URL" to "https://jenkins.example.com/",
                "CHANGE_URL" to
                    "https://eu-west-1.console.aws.amazon.com/codesuite/codecommit/repositories/backend-service/pull-requests/99/details"
            )

            val ciEnv = provider.detectCiEnvironment { env[it] }

            assertNotNull(ciEnv)
            assertEquals("awscodecommit", ciEnv.platform)
            assertEquals("backend-service", ciEnv.projectId)
            assertEquals("99", ciEnv.mrId)
            assertEquals("https://codecommit.amazonaws.com", ciEnv.baseUrl)
        }
    }

    @Test
    fun testParseUnknownChangeUrl() {
        val result = JenkinsProvider.parseChangeUrl(
            "https://unknown-platform.com/some/path"
        )

        assertNull(result)
    }

    // -- Delegation (unsupported operations) --

    @Test
    fun testFetchMergeRequestThrowsUnsupportedOperation() =
        runTest {
            assertFailsWith<UnsupportedOperationException> {
                provider.fetchMergeRequest("group/project", "123")
            }
        }

    @Test
    fun testFetchMergeRequestByUrlThrowsUnsupportedOperation() =
        runTest {
            assertFailsWith<UnsupportedOperationException> {
                provider.fetchMergeRequestByUrl("https://gitlab.com/group/project/-/merge_requests/123")
            }
        }

    // -- parseMergeRequestUrl delegation --

    @Test
    fun testParseMergeRequestUrlDelegatesToParseChangeUrl() {
        runTest {
            val result = provider.parseMergeRequestUrl(
                "https://github.com/org/repo/pull/456"
            )

            assertNotNull(result)
            assertEquals("github", result.platform)
            assertEquals("org/repo", result.projectId)
            assertEquals("456", result.mrId)
        }
    }

    // -- changeUrl config fallback and re-initialization --

    @Test
    fun `configured changeUrl is used when CHANGE_URL is unset and follows reinitialization`() =
        runTest {
            val env = mapOf("JENKINS_URL" to "https://jenkins.example.com")

            assertTrue(provider.initialize(context("""{"changeUrl": "https://github.com/org/repo/pull/1"}""")))
            assertEquals("1", provider.detectCiEnvironment { env[it] }?.mrId)

            assertTrue(provider.initialize(context("""{"changeUrl": "https://github.com/org/repo/pull/2"}""")))
            assertEquals("2", provider.detectCiEnvironment { env[it] }?.mrId)

            val withEnv = env + ("CHANGE_URL" to "https://github.com/org/repo/pull/3")
            assertEquals("3", provider.detectCiEnvironment { withEnv[it] }?.mrId, "CHANGE_URL wins over config")

            assertTrue(provider.initialize(context("{}")))
            assertNull(provider.detectCiEnvironment { env[it] })
        }

    @Test
    fun `initialize after shutdown applies the new config`() =
        runTest {
            val env = mapOf("JENKINS_URL" to "https://jenkins.example.com")

            assertTrue(provider.initialize(context("""{"changeUrl": "https://github.com/org/repo/pull/1"}""")))
            assertEquals("1", provider.detectCiEnvironment { env[it] }?.mrId)

            provider.shutdown()
            provider.shutdown()

            assertTrue(provider.initialize(context("""{"changeUrl": "https://github.com/org/repo/pull/2"}""")))
            assertEquals("2", provider.detectCiEnvironment { env[it] }?.mrId)
        }

    private fun context(config: String): PluginContext =
        object : PluginContext {
            override val pluginConfig = KarandashJson.Default.parseToJsonElement(config)
            override val hostVersion = "test"
            override val workingDirectory = "."

            override fun env(name: String): String? = null

            override fun log(message: String) {}

            override fun warn(message: String) {}
        }
}
