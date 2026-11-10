package io.karandash.providers.common

import io.karandash.http.HttpAuth
import io.karandash.testfixtures.TestPluginContext
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class EnrichmentHelpersTest {
    @Test
    fun `atlassian auth is Bearer without an email and Basic with one`() {
        assertIs<HttpAuth.Bearer>(EnrichmentHttp.atlassianAuth(null, "pat"))
        assertIs<HttpAuth.Bearer>(EnrichmentHttp.atlassianAuth(" ", "pat"))
        assertEquals(HttpAuth.Basic("me@acme.com", "t"), EnrichmentHttp.atlassianAuth("me@acme.com", "t"))
    }

    @Test
    fun `client reaches localhost only when the context allows it`() =
        runTest {
            val hits = mutableListOf<String>()
            val engine = MockEngine { request ->
                hits += request.url.host
                respond("ok", HttpStatusCode.OK)
            }
            val url = "http://localhost:8080"
            val blocked = EnrichmentHttp.client(TestPluginContext(), "Test", url, HttpAuth.Bearer("t"), engine)
            assertFailsWith<Exception> { blocked.get("$url/x") }
            blocked.close()
            assertEquals(emptyList(), hits)

            val allowed = EnrichmentHttp.client(TestPluginContext(allowLocalhost = true), "Test", url, HttpAuth.Bearer("t"), engine)
            allowed.get("$url/x")
            allowed.close()
            assertEquals(listOf("localhost"), hits)
        }

    @Test
    fun `requests beyond the per minute limit wait for the window`() =
        runTest {
            val limiter = CachedEnrichment(TestPluginContext(), "test", maxRequestsPerMinute = 2, timeSource = testScheduler.timeSource)
            val starts = mutableListOf<Long>()
            repeat(3) { limiter.request { starts += currentTime } }

            assertEquals(listOf(0L, 0L, 60_000L), starts)
        }
}
