package io.karandash.plugins.ai.prompts

import io.karandash.crypto.sha256Hex
import io.karandash.duration.KarandashDuration
import io.karandash.testfixtures.TestPluginContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemotePromptFetcherTest {
    private fun mockClient(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpClient =
        HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = body,
                        status = status,
                        headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                    )
                }
            }
        }

    @Test
    fun `fetch returns text from HTTPS URL`() =
        runTest {
            val client = mockClient("Hello prompt")
            val fetcher = RemotePromptFetcher(client)

            val result = fetcher.fetch(PromptSource.Url(url = "https://example.com/prompt.txt"))
            assertTrue(result.isSuccess)
            assertEquals("Hello prompt", result.getOrNull())
            client.close()
        }

    @Test
    fun `fetch rejects HTTP URLs`() =
        runTest {
            val client = mockClient("body")
            val fetcher = RemotePromptFetcher(client)

            val result = fetcher.fetch(PromptSource.Url(url = "http://insecure.com/prompt.txt"))
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("HTTPS") == true)
            client.close()
        }

    @Test
    fun `fetch rejects response exceeding size limit`() =
        runTest {
            val largeBody = "x".repeat(200_000)
            val client = mockClient(largeBody)
            val fetcher = RemotePromptFetcher(client, maxResponseChars = 100_000)

            val result = fetcher.fetch(PromptSource.Url(url = "https://example.com/big.txt"))
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("too large") == true)
            client.close()
        }

    @Test
    fun `fetch caches results within TTL`() =
        runTest {
            var callCount = 0
            val client = HttpClient(MockEngine) {
                engine {
                    addHandler {
                        callCount++
                        respond(
                            content = "cached prompt",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                        )
                    }
                }
            }
            val fetcher = RemotePromptFetcher(client, cacheTtl = KarandashDuration.ofMinutes(1))
            val source = PromptSource.Url(url = "https://example.com/prompt.txt")

            fetcher.fetch(source)
            fetcher.fetch(source)

            assertEquals(1, callCount)
            client.close()
        }

    @Test
    fun `fetch rejects non-2xx status codes`() =
        runTest {
            val client = mockClient("Not Found", HttpStatusCode.NotFound)
            val fetcher = RemotePromptFetcher(client)

            val result = fetcher.fetch(PromptSource.Url(url = "https://example.com/missing.txt"))
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("HTTP 404") == true)
            client.close()
        }

    @Test
    fun `extractTomlSection extracts triple-quoted string`() {
        val client = mockClient("")
        val fetcher = RemotePromptFetcher(client)

        val toml = """
            [other]
            key = "value"

            [review.system]
            prompt = ${"\"\"\""}
            You are a code reviewer.
            Be thorough.
            ${"\"\"\""}

            [review.user]
            prompt = "user prompt"
        """.trimIndent()

        val result = fetcher.extractTomlSection(toml, "review.system")
        assertTrue(result.contains("You are a code reviewer."))
        assertTrue(result.contains("Be thorough."))
        client.close()
    }

    @Test
    fun `a missing TOML section fails instead of returning the whole document`() =
        runTest {
            val client = mockClient("[other]\nkey = \"value\"")
            val fetcher = RemotePromptFetcher(client)

            val result = fetcher.fetch(PromptSource.Url(url = "https://example.com/p.toml", format = "toml", section = "nonexistent"))

            assertTrue(result.isFailure, "got ${result.getOrNull()}")
            assertTrue(result.exceptionOrNull()?.message?.contains("nonexistent") == true)
            client.close()
        }

    @Test
    fun `a cached prompt is not served to a source with another pin or section`() =
        runTest {
            val toml = "[a]\nprompt = \"PROMPT A\"\n[b]\nprompt = \"PROMPT B\""
            val client = mockClient(toml)
            val fetcher = RemotePromptFetcher(client)
            val url = "https://example.com/p.toml"

            assertEquals("PROMPT A", fetcher.fetch(PromptSource.Url(url, "toml", "a", sha256Hex("PROMPT A"))).getOrNull())
            val wrongPin = fetcher.fetch(PromptSource.Url(url, "toml", "a", "0".repeat(64)))
            val otherSection = fetcher.fetch(PromptSource.Url(url, "toml", "b"))

            assertTrue(wrongPin.isFailure, "a wrong pin was served ${wrongPin.getOrNull()}")
            assertEquals("PROMPT B", otherSection.getOrNull())
            client.close()
        }

    @Test
    fun `a multibyte body over the limit is rejected`() =
        runTest {
            val client = mockClient("\u00e9".repeat(60))
            val fetcher = RemotePromptFetcher(client, maxResponseChars = 50)

            val result = fetcher.fetch(PromptSource.Url(url = "https://example.com/big.txt"))

            assertTrue(result.exceptionOrNull()?.message?.contains("too large") == true, "$result")
            client.close()
        }

    @Test
    fun `extractTomlSection with null section returns full text`() {
        val client = mockClient("")
        val fetcher = RemotePromptFetcher(client)
        val toml = "some text"

        val result = fetcher.extractTomlSection(toml, null)
        assertEquals(toml, result)
        client.close()
    }

    @Test
    fun `fetch validates SHA-256 when provided`() =
        runTest {
            val body = "You are a code reviewer."
            val hash = sha256Hex(body)
            val client = mockClient(body)
            val fetcher = RemotePromptFetcher(client)

            val result = fetcher.fetch(PromptSource.Url(url = "https://example.com/prompt.txt", sha256 = hash))
            assertTrue(result.isSuccess)
            assertEquals(body, result.getOrNull())
            client.close()
        }

    @Test
    fun `fetch rejects SHA-256 mismatch`() =
        runTest {
            val client = mockClient("actual content")
            val fetcher = RemotePromptFetcher(client)

            val result = fetcher.fetch(
                PromptSource.Url(
                    url = "https://example.com/prompt.txt",
                    sha256 = "0000000000000000000000000000000000000000000000000000000000000000"
                )
            )
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("SHA-256 mismatch") == true)
            client.close()
        }

    @Test
    fun `fetch skips SHA-256 check when hash is null`() =
        runTest {
            val client = mockClient("no hash check")
            val fetcher = RemotePromptFetcher(client)

            val result = fetcher.fetch(PromptSource.Url(url = "https://example.com/prompt.txt"))
            assertTrue(result.isSuccess)
            client.close()
        }

    @Test
    fun `extractTomlSection returns the raw section body when no prompt string is quoted`() {
        val client = mockClient("")
        val fetcher = RemotePromptFetcher(client)

        val result = fetcher.extractTomlSection("[a]\nflag = true\n[b]\nx = 1", "a")

        assertEquals("flag = true", result)
        client.close()
    }

    @Test
    fun `logger based constructor builds an owned client that close releases`() {
        val fetcher = RemotePromptFetcher(TestPluginContext().logger, allowLocalhost = true)
        fetcher.close()
        fetcher.close()
    }
}
