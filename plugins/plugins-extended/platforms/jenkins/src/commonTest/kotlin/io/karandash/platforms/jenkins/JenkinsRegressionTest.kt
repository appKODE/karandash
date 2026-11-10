package io.karandash.platforms.jenkins

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Jenkins reads the build result from its JSON API using JenkinsCredentials. */
class JenkinsRegressionTest {
    private val env = mapOf(
        "JENKINS_URL" to "https://jenkins.example.com/",
        "BUILD_URL" to "https://jenkins.example.com/job/app/job/PR-7/12/",
        "JENKINS_USER" to "bot",
        "JENKINS_API_TOKEN" to "tok",
    )

    private suspend fun status(
        body: String,
        code: HttpStatusCode = HttpStatusCode.OK,
        requests: MutableList<Pair<String, String?>> = mutableListOf(),
    ): String? {
        val engine = MockEngine { request ->
            requests += request.url.encodedPath to request.headers[HttpHeaders.Authorization]
            respond(body, code, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return JenkinsProvider(ModuleRegistryImpl(), engine).fetchCiStatus { env[it] }
    }

    @Test
    fun `a successful build is success and authenticates with basic auth`() =
        runTest {
            val requests = mutableListOf<Pair<String, String?>>()
            assertEquals("success", status("""{"building":false,"result":"SUCCESS"}""", requests = requests))
            assertEquals("/job/app/job/PR-7/12/api/json", requests.single().first)
            assertEquals("Basic Ym90OnRvaw==", requests.single().second)
        }

    @Test
    fun `a failed or unstable build is failed`() =
        runTest {
            assertEquals("failed", status("""{"building":false,"result":"FAILURE"}"""))
            assertEquals("failed", status("""{"building":false,"result":"UNSTABLE"}"""))
        }

    @Test
    fun `a running build is pending`() =
        runTest {
            assertEquals("pending", status("""{"building":true,"result":null}"""))
        }

    @Test
    fun `an error response gives no status`() =
        runTest {
            assertNull(status("denied", HttpStatusCode.Forbidden))
        }

    @Test
    fun `outside Jenkins there is no status`() =
        runTest {
            assertNull(JenkinsProvider(ModuleRegistryImpl()).fetchCiStatus { null })
        }

    @Test
    fun `config url user and token beat the Jenkins env vars`() =
        runTest {
            val requests = mutableListOf<Pair<String, String?>>()
            val engine = MockEngine { request ->
                requests += request.url.host to request.headers[HttpHeaders.Authorization]
                respond(
                    """{"building":false,"result":"SUCCESS"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val config = mapOf("baseUrl" to "https://ci.example.com", "user" to "cfg", "token" to "secret")
            val provider = JenkinsProvider(ModuleRegistryImpl(), engine)
            assertTrue(provider.initialize(context(JsonObject(config.mapValues { JsonPrimitive(it.value) }))))
            val buildOnly = mapOf("BUILD_URL" to "https://ci.example.com/job/app/3/", "JENKINS_USER" to "bot", "JENKINS_API_TOKEN" to "tok")

            assertEquals("success", provider.fetchCiStatus { buildOnly[it] })
            assertEquals("ci.example.com" to "Basic Y2ZnOnNlY3JldA==", requests.single())
        }

    private fun context(config: JsonObject) =
        object : PluginContext {
            override val pluginConfig = config
            override val hostVersion = "test"
            override val workingDirectory = "."

            override fun env(name: String): String? = null

            override fun log(message: String) = Unit

            override fun warn(message: String) = Unit
        }
}
