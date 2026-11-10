package io.karandash.http

import io.karandash.logger.Logger
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpLoggingPluginTest {
    private class CapturingLogger : Logger {
        val messages = mutableListOf<Pair<String, String>>()

        override val isDebugEnabled: Boolean get() = true

        override fun debug(message: String) {
            messages.add("debug" to message)
        }

        override fun info(message: String) {
            messages.add("info" to message)
        }

        override fun warn(message: String) {
            messages.add("warn" to message)
        }

        override fun error(message: String) {
            messages.add("error" to message)
        }
    }

    @Test
    fun `logs request method and URL on send`() =
        runTest {
            val logger = CapturingLogger()
            val mockEngine = MockEngine {
                respond(
                    content = """{"ok":true}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }

            val client = ProviderHttpClientFactory.create(engine = mockEngine, logger = logger)
            client.get("https://api.example.com/test/endpoint").bodyAsText()
            client.close()

            val requestLog = logger.messages.first { it.second.startsWith("HTTP GET") && !it.second.contains("->") }
            assertEquals("debug", requestLog.first)
            assertTrue(requestLog.second.contains("https://api.example.com/test/endpoint"))
        }

    @Test
    fun `logs response status and duration`() =
        runTest {
            val logger = CapturingLogger()
            val mockEngine = MockEngine {
                respond(
                    content = """{"ok":true}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }

            val client = ProviderHttpClientFactory.create(engine = mockEngine, logger = logger)
            client.get("https://api.example.com/data").bodyAsText()
            client.close()

            val responseLog = logger.messages.first { it.second.contains("->") }
            assertEquals("debug", responseLog.first)
            assertTrue(responseLog.second.contains("200 OK"))
            assertTrue(responseLog.second.matches(Regex(""".*\(\d+ms\)""")))
        }

    @Test
    fun `does not log headers`() =
        runTest {
            val logger = CapturingLogger()
            val mockEngine = MockEngine {
                respond(
                    content = """{"ok":true}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }

            val client = ProviderHttpClientFactory.create(engine = mockEngine, logger = logger)
            client
                .get("https://api.example.com/secure") {
                    headers.append("Authorization", "Bearer secret-token-123")
                    headers.append("PRIVATE-TOKEN", "glpat-secret")
                }.bodyAsText()
            client.close()

            val allLogText = logger.messages.joinToString("\n") { it.second }
            assertTrue(!allLogText.contains("secret-token-123"), "Auth token must not appear in logs")
            assertTrue(!allLogText.contains("glpat-secret"), "Private token must not appear in logs")
            assertTrue(!allLogText.contains("Authorization"), "Authorization header name must not appear in logs")
            assertTrue(!allLogText.contains("PRIVATE-TOKEN"), "Private-Token header name must not appear in logs")
        }

    @Test
    fun `NoOpLogger default produces no logging overhead`() =
        runTest {
            val mockEngine = MockEngine {
                respond(
                    content = """{"ok":true}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }

            val client = ProviderHttpClientFactory.create(engine = mockEngine)
            client.get("https://api.example.com/test").bodyAsText()
            client.close()
        }

    @Test
    fun `createProvider with logger produces debug output`() =
        runTest {
            val logger = CapturingLogger()
            val mockEngine = MockEngine {
                respond(
                    content = """{"items":[]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }

            val client = ProviderHttpClientFactory.create(
                engine = mockEngine,
                logger = logger,
            )
            client.get("https://gitlab.com/api/v4/projects/group%2Frepo/merge_requests/42").bodyAsText()
            client.close()

            assertTrue(logger.messages.isNotEmpty(), "Logger should have captured messages")
            val debugMessages = logger.messages.filter { it.first == "debug" }
            assertEquals(2, debugMessages.size, "Should have exactly 2 debug messages (request + response)")
            assertTrue(debugMessages[0].second.contains("HTTP GET"))
            assertTrue(debugMessages[1].second.contains("-> 200 OK"))
        }

    @Test
    fun `logs ERROR line and rethrows when engine throws during proceed`() =
        runTest {
            val logger = CapturingLogger()
            // Engine throws directly - simulates curl/native engine failure where proceed() throws
            val mockEngine = MockEngine {
                throw IllegalStateException("Connection refused")
            }

            val client = ProviderHttpClientFactory.create(engine = mockEngine, logger = logger)
            assertFailsWith<IllegalStateException> {
                client.get("https://api.example.com/test/endpoint")
            }
            client.close()

            val errorLog = logger.messages.first { it.second.contains("-> ERROR") }
            assertEquals("debug", errorLog.first)
            assertTrue(
                errorLog.second.matches(Regex("""HTTP GET .* -> ERROR \(\d+ms\):.*""")),
                "Error log should match pattern: ${errorLog.second}",
            )
        }

    @Test
    fun `logs non-success status codes`() =
        runTest {
            val logger = CapturingLogger()
            val mockEngine = MockEngine {
                respond(
                    content = """{"error":"not found"}""",
                    status = HttpStatusCode.NotFound,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }

            val client = ProviderHttpClientFactory.create(engine = mockEngine, logger = logger)
            client.get("https://api.example.com/missing").bodyAsText()
            client.close()

            val responseLog = logger.messages.first { it.second.contains("->") }
            assertTrue(responseLog.second.contains("404 Not Found"))
        }

    @Test
    fun `URL credentials - token query values and webhook paths are not logged`() =
        runTest {
            val logger = CapturingLogger()
            val mockEngine = MockEngine { respond(content = "ok", status = HttpStatusCode.OK) }
            val client = ProviderHttpClientFactory.create(engine = mockEngine, logger = logger)
            listOf(
                "https://alice:hunter2pass@config.example.com/base.yaml",
                "https://gitlab.example.com/api/v4/projects?private_token=gltokensecret1&page=2",
                "https://maps.example.com/v1?key=googlekeysecret2",
                "https://api.example.com/me?access_token=oauthsecret3",
                "https://hooks.slack.com/services/T0001/B0002/slacksecret4",
                "https://discord.com/api/webhooks/123/discordsecret5",
            ).forEach { client.get(it).bodyAsText() }
            client.close()

            val allLogText = logger.messages.joinToString("\n") { it.second }
            listOf("hunter2pass", "gltokensecret1", "googlekeysecret2", "oauthsecret3", "slacksecret4", "discordsecret5")
                .forEach { assertTrue(it !in allLogText, "secret '$it' leaked into logs:\n$allLogText") }
            assertTrue("gitlab.example.com/api/v4/projects?private_token=[REDACTED]&page=2" in allLogText)
            assertTrue("hooks.slack.com/services/[REDACTED]" in allLogText)
        }
}
