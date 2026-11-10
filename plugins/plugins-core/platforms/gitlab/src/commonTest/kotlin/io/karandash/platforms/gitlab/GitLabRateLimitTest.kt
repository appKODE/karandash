package io.karandash.platforms.gitlab

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitLabRateLimitTest {
    @Test
    fun `lastRateLimit captured from RateLimit headers`() =
        runTest {
            val engine = MockEngine {
                respond(
                    content = "",
                    status = HttpStatusCode.NoContent,
                    headers = headersOf(
                        "RateLimit-Remaining" to listOf("7"),
                        "RateLimit-Limit" to listOf("600"),
                        "RateLimit-Reset" to listOf("1760000123"),
                    ),
                )
            }
            val client = GitLabClient(httpClient = HttpClient(engine), responseCache = null)
            assertNull(client.lastRateLimit())

            client.checkAuth("https://gitlab.com/api/v4/user")

            val info = client.lastRateLimit()
            assertEquals(7, info?.remaining)
            assertEquals(600, info?.limit)
            assertEquals(1760000123L, info?.resetEpochSeconds)
        }

    @Test
    fun `service rateLimitStatus is null before init`() = runTest { assertNull(GitLabPlatformService().rateLimitStatus()) }
}
