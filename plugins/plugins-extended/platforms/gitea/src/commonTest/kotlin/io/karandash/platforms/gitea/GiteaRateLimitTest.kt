package io.karandash.platforms.gitea

import io.karandash.api.platform.RateLimitInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GiteaRateLimitTest {
    private suspend fun serviceAnswering(headers: Headers): GiteaPlatformService {
        val http = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK, headers) })
        val service = GiteaPlatformService()
        service.commentClient.rebuild("test") { GiteaClient(baseUrl = "https://gitea.test", token = "t", httpClient = http) }
        return service
    }

    @Test
    fun `rateLimitStatus reads the X-RateLimit headers of the last response`() =
        runTest {
            val service = serviceAnswering(
                headersOf(
                    "X-RateLimit-Remaining" to listOf("42"),
                    "X-RateLimit-Limit" to listOf("300"),
                    "X-RateLimit-Reset" to listOf("1760000123"),
                ),
            )
            assertNull(service.rateLimitStatus())

            service.checkAuth()

            assertEquals(RateLimitInfo(remaining = 42, limit = 300, resetEpochSeconds = 1760000123), service.rateLimitStatus())
        }

    @Test
    fun `rateLimitStatus stays null when the instance sends no rate-limit headers`() =
        runTest {
            val service = serviceAnswering(Headers.Empty)

            service.checkAuth()

            assertNull(service.rateLimitStatus())
        }
}
