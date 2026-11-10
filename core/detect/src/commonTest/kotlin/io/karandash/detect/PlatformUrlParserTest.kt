package io.karandash.detect

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlatformUrlParserTest {
    @Test
    fun firstProviderThatParsesTheUrlWinsAndLaterOnesAreNotAsked() =
        runTest {
            val first = FakePlatformService(id = "gitlab", urlPrefix = "https://gitlab.example.com")
            val second = FakePlatformService(id = "other", urlPrefix = "https://gitlab.example.com")

            val parsed = PlatformUrlParser(listOf(first, second)).parse("https://gitlab.example.com/g/p/-/merge_requests/7")

            assertEquals("gitlab", parsed?.platform)
            assertEquals("7", parsed?.mrId)
            assertEquals(emptyList(), second.parsedUrls)
        }

    @Test
    fun providerReportingAModuleIdGetsTheCanonicalPlatform() =
        runTest {
            val provider = FakePlatformService(
                id = "azure-devops",
                aliases = setOf("io.karandash.platform.azure"),
                urlPrefix = "https://dev.azure.com",
                reportedPlatform = "io.karandash.platform.azure",
            )

            val parsed = PlatformUrlParser(listOf(provider)).parse("https://dev.azure.com/org/p/_git/r/pullrequest/7")

            assertEquals("azure-devops", parsed?.platform)
        }

    @Test
    fun httpSchemeIsUpgradedButHttpInsideTheUrlIsLeftAlone() =
        runTest {
            val provider = FakePlatformService(id = "gitea", urlPrefix = "https://git.example.com")

            val parsed = PlatformUrlParser(listOf(provider)).parse("http://git.example.com/o/r/pulls/7?back=http://ci.example.com")

            assertEquals("gitea", parsed?.platform)
            assertEquals(listOf("https://git.example.com/o/r/pulls/7?back=http://ci.example.com"), provider.parsedUrls)
        }

    @Test
    fun urlNoProviderUnderstandsIsNull() =
        runTest {
            val provider = FakePlatformService(id = "github", urlPrefix = "https://github.com")

            assertNull(PlatformUrlParser(listOf(provider)).parse("https://example.com/not/a/pr"))
            assertNull(PlatformUrlParser(emptyList()).parse("https://github.com/o/r/pull/1"))
        }
}
