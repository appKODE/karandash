package io.karandash.detect

import io.karandash.api.platform.CIEnvironment
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlatformDetectorTest {
    private val ciEnv = CIEnvironment(platform = "io.karandash.platform.gitlab", projectId = "g/p", mrId = "3", token = "t", baseUrl = null)
    private val gitlab = FakePlatformService(
        id = "gitlab",
        aliases = setOf("io.karandash.platform.gitlab"),
        apiUrl = "https://gitlab.com/api/v4",
        ci = ciEnv,
        remoteHost = "gitlab.com",
    )
    private val github = FakePlatformService(id = "github", remoteHost = "github.com")

    private fun detector(
        providers: List<FakePlatformService>,
        env: Map<String, String> = emptyMap(),
        remote: String? = null,
    ) = object : PlatformDetector(providers = providers, envProvider = { env[it] }) {
        override fun readGitRemote(): String? = remote
    }

    @Test
    fun explicitEnvWinsOverCiAndResolvesAnAliasToTheProviderId() =
        runTest {
            val env = mapOf(
                "KARANDASH_PLATFORM" to "io.karandash.platform.gitlab",
                "KARANDASH_PROJECT_ID" to "explicit/project",
                "KARANDASH_MR_ID" to "42",
                "KARANDASH_BASE_URL" to "https://self-hosted/api",
                "KARANDASH_TOKEN" to "secret",
            )

            val result = detector(listOf(gitlab), env).detect()

            assertEquals(
                DetectionResult(
                    "gitlab",
                    "explicit/project",
                    "42",
                    "https://self-hosted/api",
                    "secret",
                    DetectionConfidence.HIGH,
                    "explicit"
                ),
                result,
            )
        }

    @Test
    fun unknownExplicitPlatformIsKeptAsWritten() =
        runTest {
            val result = detector(listOf(gitlab), mapOf("KARANDASH_PLATFORM" to "forgejo")).detectFromEnv()

            assertEquals("forgejo", result?.platform)
        }

    @Test
    fun ciDetectionCanonicalizesThePlatformAndFallsBackToTheProviderApiUrl() =
        runTest {
            val result = detector(listOf(github, gitlab)).detect()

            assertEquals(
                DetectionResult("gitlab", "g/p", "3", "https://gitlab.com/api/v4", "t", DetectionConfidence.HIGH, "ci:gitlab"),
                result,
            )
        }

    @Test
    fun gitRemoteIsTheMediumConfidenceLastResort() =
        runTest {
            val noCi = FakePlatformService(id = "github", remoteHost = "github.com", apiUrl = "https://api.github.com")

            val result = detector(listOf(noCi), remote = "  git@github.com:owner/repo.git\n").detect()

            assertEquals(
                DetectionResult(
                    "github",
                    "owner/repo",
                    null,
                    "https://api.github.com",
                    null,
                    DetectionConfidence.MEDIUM,
                    "git-remote:github"
                ),
                result,
            )
        }

    @Test
    fun nothingDetectedWhenNoStrategyMatches() =
        runTest {
            assertNull(detector(listOf(github)).detect(), "no env, no CI, no remote")
            assertNull(detector(listOf(github), remote = "https://bitbucket.org/a/b.git").detect(), "unparseable remote")
        }

    @Test
    fun `CI detection picks the provider with the best score doctor shows`() =
        runTest {
            val weak = FakePlatformService(id = "weak", ci = ciEnv.copy(platform = "weak"), score = 0.4)
            val strong = FakePlatformService(id = "strong", ci = ciEnv.copy(platform = "strong"), score = 0.95)

            assertEquals("strong", CIDetector(listOf(weak, strong)).detect { null }?.platform)
            assertEquals("strong", detector(listOf(weak, strong)).detect()?.platform)
        }

    @Test
    fun `a viable score without a CI environment is a LOW detection`() =
        runTest {
            val heuristic = FakePlatformService(id = "heuristic", apiUrl = "https://h/api", score = 0.5)

            assertEquals(
                DetectionResult("heuristic", null, null, "https://h/api", null, DetectionConfidence.LOW, "test:heuristic"),
                detector(listOf(heuristic)).detect(),
            )
            assertNull(detector(listOf(FakePlatformService(id = "weak", score = 0.1))).detect(), "below the viable score")
        }
}
