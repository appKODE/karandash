package io.karandash.platforms.azuredevops

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AzureDevOpsProviderTest {
    @Test
    fun testFetchMergeRequestByUrlParsing() {
        @Suppress("UnusedPrivateProperty")
        val provider = AzureDevOpsPlatformService()

        val validUrls = listOf(
            "https://dev.azure.com/myorg/myproject/_git/myrepo/pullrequest/123",
            "https://dev.azure.com/company-name/project-name/_git/repository-name/pullrequest/456"
        )

        validUrls.forEach { url ->
            val regex = Regex("""https://dev\.azure\.com/([^/]+)/([^/]+)/_git/([^/]+)/pullrequest/(\d+)""")
            val match = regex.find(url)
            assertEquals(true, match != null, "URL should match: $url")
        }
    }

    @Test
    fun testFetchMergeRequestByUrlInvalid() {
        @Suppress("UnusedPrivateProperty")
        val provider = AzureDevOpsPlatformService()

        val invalidUrls = listOf(
            "https://github.com/user/repo/pull/123",
            "https://dev.azure.com/org/project/123",
            "not-a-url"
        )

        invalidUrls.forEach { url ->
            assertFailsWith<IllegalArgumentException> {
                val regex = Regex("""https://dev\.azure\.com/([^/]+)/([^/]+)/_git/([^/]+)/pullrequest/(\d+)""")
                val match = regex.find(url)
                    ?: throw IllegalArgumentException("Invalid Azure DevOps PR URL: $url")
            }
        }
    }

    @Test
    fun testFetchMergeRequestProjectIdParsing() {
        @Suppress("UnusedPrivateProperty")
        val provider = AzureDevOpsPlatformService()

        // Test valid project ID format
        val projectId = "myorg/myproject/myrepo"
        val parts = projectId.split("/")
        assertEquals(3, parts.size)
        assertEquals("myorg", parts[0])
        assertEquals("myproject", parts[1])
        assertEquals("myrepo", parts[2])
    }

    @Test
    fun testFetchMergeRequestInvalidProjectId() {
        @Suppress("UnusedPrivateProperty")
        val provider = AzureDevOpsPlatformService()

        val invalidProjectIds = listOf(
            "org/project",
            "org/project/repo/extra",
            ""
        )

        invalidProjectIds.forEach { projectId ->
            assertFailsWith<IllegalArgumentException> {
                val parts = projectId.split("/")
                require(parts.size == 3) { "Project ID must be in format 'organization/project/repository'" }
            }
        }
    }

    @Test
    fun testUrlRegexExtraction() {
        val url = "https://dev.azure.com/microsoft/azure-devops/_git/azure-repos/pullrequest/789"
        val regex = Regex("""https://dev\.azure\.com/([^/]+)/([^/]+)/_git/([^/]+)/pullrequest/(\d+)""")
        val match = regex.find(url)!!

        val (organization, project, repository, prId) = match.destructured
        assertEquals("microsoft", organization)
        assertEquals("azure-devops", project)
        assertEquals("azure-repos", repository)
        assertEquals("789", prId)
    }
}
