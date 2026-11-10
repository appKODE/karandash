package io.karandash.platforms.jenkins

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.platform.PlatformOperation
import io.karandash.api.platform.UnsupportedPlatformOperationException
import io.karandash.models.DiffRefs
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JenkinsClientErrorTest {
    private val env = mapOf(
        "JENKINS_URL" to "https://jenkins.example.com/",
        "BUILD_URL" to "https://jenkins.example.com/job/app/12/",
    )

    @Test
    fun `an unreadable body gives no status`() =
        runTest {
            val engine = MockEngine { respond("not json", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
            assertNull(JenkinsProvider(ModuleRegistryImpl(), engine).fetchCiStatus { env[it] })
        }

    @Test
    fun `a transport failure gives no status`() =
        runTest {
            val engine = MockEngine { error("connection reset") }
            assertNull(JenkinsProvider(ModuleRegistryImpl(), engine).fetchCiStatus { env[it] })
        }

    @Test
    fun `a missing build url gives no status`() =
        runTest {
            assertNull(JenkinsProvider(ModuleRegistryImpl()).fetchCiStatus { if (it == "JENKINS_URL") "https://j/" else null })
        }

    @Test
    fun `fetching and commenting are unsupported and no operation is declared`() =
        runTest {
            val p = JenkinsProvider(ModuleRegistryImpl())
            assertFailsWith<UnsupportedPlatformOperationException> { p.fetchMergeRequest("p", "1") }
            assertFailsWith<UnsupportedPlatformOperationException> { p.fetchMergeRequestByUrl("https://x/y") }
            assertFailsWith<UnsupportedPlatformOperationException> { p.postComment("p", "1", "b") }
            assertFailsWith<UnsupportedPlatformOperationException> { p.updateComment("p", "1", "c", "b") }
            assertFailsWith<UnsupportedPlatformOperationException> { p.findExistingComment("p", "1", "m") }
            assertFailsWith<UnsupportedPlatformOperationException> {
                p.postInlineComment("p", "1", "b", "f.kt", 1, DiffRefs("a", "b", "c"), null)
            }
            // Jenkins only reads the CI status; every merge-request operation belongs to the underlying platform.
            assertEquals(emptyList(), PlatformOperation.entries.filter { p.supports(it) })
        }

    @Test
    fun `messages report the missing environment`() =
        runTest {
            val messages = JenkinsProvider(ModuleRegistryImpl()).messages()
            assertEquals(1, messages.size)
            assertTrue(messages.single().toString().contains("JENKINS_URL"))
        }

    @Test
    fun `a build url on another host is never requested`() =
        runTest {
            val engine = MockEngine { respond("""{"result":"SUCCESS"}""", HttpStatusCode.OK, jsonHeaders) }
            val other = env + ("BUILD_URL" to "https://evil.example.org/job/app/12/")
            assertNull(JenkinsProvider(ModuleRegistryImpl(), engine).fetchCiStatus { other[it] })
            assertEquals(0, engine.requestHistory.size)
        }

    @Test
    fun `a loopback or metadata jenkins url is refused without allow_localhost`() =
        runTest {
            listOf("http://localhost:8080/", "https://169.254.169.254/", "ftp://jenkins.example.com/").forEach { url ->
                val engine = MockEngine { respond("""{"result":"SUCCESS"}""", HttpStatusCode.OK, jsonHeaders) }
                val local = mapOf("JENKINS_URL" to url, "BUILD_URL" to url + "job/app/1/")
                assertNull(JenkinsProvider(ModuleRegistryImpl(), engine).fetchCiStatus { local[it] }, url)
                assertEquals(0, engine.requestHistory.size, url)
            }
        }

    @Test
    fun `allow_localhost lets a loopback jenkins url through`() =
        runTest {
            val engine = MockEngine { respond("""{"result":"SUCCESS"}""", HttpStatusCode.OK, jsonHeaders) }
            val provider = JenkinsProvider(ModuleRegistryImpl(), engine)
            assertTrue(provider.initialize(localhostContext))
            val local = mapOf("JENKINS_URL" to "http://localhost:8080/", "BUILD_URL" to "http://localhost:8080/job/app/1/")
            assertEquals("success", provider.fetchCiStatus { local[it] })
        }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private val localhostContext =
        object : PluginContext {
            override val pluginConfig = JsonObject(emptyMap())
            override val hostVersion = "test"
            override val workingDirectory = "."
            override val allowLocalhost = true

            override fun env(name: String): String? = null

            override fun log(message: String) = Unit

            override fun warn(message: String) = Unit
        }
}
