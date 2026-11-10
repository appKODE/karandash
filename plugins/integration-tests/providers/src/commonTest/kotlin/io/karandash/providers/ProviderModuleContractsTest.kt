package io.karandash.providers

import io.karandash.api.ModuleType
import io.karandash.platforms.awscodecommit.AwsCodeCommitPlatformService
import io.karandash.platforms.azuredevops.AzureDevOpsPlatformService
import io.karandash.platforms.bitbucket.BitbucketPlatformService
import io.karandash.platforms.gerrit.GerritPlatformService
import io.karandash.platforms.gitea.GiteaPlatformService
import io.karandash.platforms.github.GitHubPlatformService
import io.karandash.platforms.gitlab.GitLabPlatformService
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderModuleContractsTest {
    @Test
    fun `platform services expose platform-service type and unique ids`() =
        runTest {
            val services = listOf(
                GitLabPlatformService(),
                GitHubPlatformService(),
                BitbucketPlatformService(),
                GerritPlatformService(),
                AzureDevOpsPlatformService(),
                AwsCodeCommitPlatformService(),
                GiteaPlatformService(),
            )

            assertTrue(services.all { it.type == ModuleType.PLATFORM_SERVICE })
            assertEquals(services.size, services.map { it.id }.toSet().size)

            services.forEach { it.shutdown() }
        }
}
