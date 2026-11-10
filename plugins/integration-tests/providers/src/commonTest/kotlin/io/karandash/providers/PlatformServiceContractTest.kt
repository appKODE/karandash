package io.karandash.providers

import io.karandash.api.module.PlatformServiceModule
import io.karandash.platforms.awscodecommit.AwsCodeCommitPlatformService
import io.karandash.platforms.azuredevops.AzureDevOpsPlatformService
import io.karandash.platforms.bitbucket.BitbucketPlatformService
import io.karandash.platforms.gerrit.GerritPlatformService
import io.karandash.platforms.gitea.GiteaPlatformService
import io.karandash.platforms.github.GitHubPlatformService
import io.karandash.platforms.gitlab.GitLabPlatformService
import io.karandash.sdk.ModuleContractTest
import io.karandash.testfixtures.TestPluginContext
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Module contract rules 1-5 (docs/development/conventions.md) for every first-party platform service. */
class PlatformServiceContractTest {
    private class Contract(
        private val factory: () -> PlatformServiceModule,
        private val valid: JsonElement,
        private val malformed: JsonElement,
    ) : ModuleContractTest<PlatformServiceModule>() {
        override fun createModule() = factory()

        override fun validConfig() = valid

        override fun malformedConfig() = malformed

        override suspend fun invokePrimaryWithEmptyInput(module: PlatformServiceModule) {
            assertNull(module.parseMergeRequestUrl(""), "${module.id}: empty MR url must not parse")
            assertNull(module.parseGitRemoteUrl(""), "${module.id}: empty remote url must not parse")
            // An empty URL is rejected with the documented error type, never an NPE or a network call.
            assertFailsWith<IllegalArgumentException> { module.fetchMergeRequestByUrl("") }
        }
    }

    private val contracts = listOf(
        Contract({ GitLabPlatformService() }, HOSTED, WRONG_TYPED_URL),
        Contract({ GitHubPlatformService() }, HOSTED, WRONG_TYPED_TOKEN),
        Contract({ BitbucketPlatformService() }, HOSTED, WRONG_TYPED_URL),
        Contract({ GerritPlatformService() }, HOSTED, INSECURE_URL),
        Contract({ AzureDevOpsPlatformService() }, HOSTED, INSECURE_URL),
        Contract(
            { AwsCodeCommitPlatformService() },
            AWS,
            obj("region" to obj("name" to JsonPrimitive("us-east-1"))),
        ),
        Contract({ GiteaPlatformService() }, HOSTED, WRONG_TYPED_URL),
    )

    @Test
    fun `initialize returns true for valid config`() = runTest { contracts.forEach { it.verifyInitializeWithValidConfig() } }

    @Test
    fun `initialize returns false for malformed config and never throws`() =
        runTest { contracts.forEach { it.verifyInitializeWithMalformedConfig() } }

    @Test
    fun `initialize with empty config registers anonymously`() =
        runTest {
            contracts.forEach { contract ->
                val module = contract.createModule()
                // No url/token in config or env: every service registers for anonymous reads (AWS: ambient credential chain);
                // a missing token surfaces through checkAuth, not initialize.
                assertTrue(
                    module.initialize(contract.testContext(JsonObject(emptyMap()))),
                    "${module.id}: {} initialize result",
                )
                module.shutdown()
            }
        }

    @Test
    fun `shutdown without initialize does not throw`() = runTest { contracts.forEach { it.verifyShutdownWithoutInitialize() } }

    @Test
    fun `shutdown is idempotent`() = runTest { contracts.forEach { it.verifyShutdownIsIdempotent() } }

    @Test
    fun `primary methods handle empty input`() = runTest { contracts.forEach { it.verifyPrimaryMethodWithEmptyInput() } }

    /** A service built over [engine], its config for a base URL and token. */
    private class Scoped(
        val build: (MockEngine) -> PlatformServiceModule,
        val config: (url: String, token: String) -> JsonElement,
    )

    private val scoped = listOf(
        Scoped({ GitLabPlatformService(it) }, ::hosted),
        Scoped({ GitHubPlatformService(it) }, ::hosted),
        Scoped({ BitbucketPlatformService(it) }, ::hosted),
        Scoped({ GerritPlatformService(it) }) { url, token -> hosted(url, token, "username" to JsonPrimitive("bot")) },
        Scoped({ AzureDevOpsPlatformService(it) }) { url, token -> hosted(url, token, "organization" to JsonPrimitive("org")) },
        Scoped({ GiteaPlatformService(it) }, ::hosted),
        Scoped({ AwsCodeCommitPlatformService(it) }) { url, token ->
            obj(
                "region" to JsonPrimitive("us-east-1"),
                "endpoint" to JsonPrimitive(url),
                "accessKeyId" to JsonPrimitive("AKIDEXAMPLE"),
                "secretAccessKey" to JsonPrimitive(token),
            )
        },
    )

    @Test
    fun `re-init with a new token and host sends the new credential and a foreign redirect carries none`() =
        runTest {
            scoped.forEach { platform ->
                val requests = mutableListOf<HttpRequestData>()
                val engine = MockEngine { request ->
                    requests += request
                    if (request.url.host == FOREIGN) {
                        respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    } else {
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://$FOREIGN/landing"))
                    }
                }
                val module = platform.build(engine)
                val first = credentialsSent(module, platform.config("https://scm.example.com", "t0ken-a"), requests)
                val second = credentialsSent(module, platform.config("https://scm2.example.com", "t0ken-b"), requests)
                module.shutdown()

                assertTrue(first.isNotEmpty(), "${module.id}: no credential sent to its own host")
                assertTrue(second.isNotEmpty(), "${module.id}: no credential sent to the new host after re-init")
                assertTrue(first.intersect(second).isEmpty(), "${module.id}: re-init kept sending the old credential")
                val leaked = requests.filter { it.url.host == FOREIGN }.flatMap(::credentialHeaders)
                assertTrue(leaked.isEmpty(), "${module.id}: redirect to a foreign host carried $leaked")
                engine.close()
            }
        }

    /** Initializes [module] with [config], makes calls on its own host; the credential values sent there. */
    private suspend fun credentialsSent(
        module: PlatformServiceModule,
        config: JsonElement,
        requests: MutableList<HttpRequestData>,
    ): Set<String> {
        assertTrue(module.initialize(TestPluginContext(config)), "${module.id}: initialize")
        val url = checkNotNull(config.jsonObject["url"] ?: config.jsonObject["endpoint"]) { "no base url" }
        val host = Url(url.jsonPrimitive.content).host
        requests.clear()
        suspendRunCatching { module.checkAuth() }
        suspendRunCatching { module.fetchMergeRequest("group/project", "1") }
        return requests.filter { it.url.host == host }.flatMap(::credentialHeaders).toSet()
    }

    private suspend fun suspendRunCatching(call: suspend () -> Unit) {
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (expected: Exception) {
            // boundary: the mock answers every call with a redirect, so the call fails; only the requests sent matter
            assertTrue(expected !is CancellationException)
        }
    }

    private fun credentialHeaders(request: HttpRequestData): List<String> = CREDENTIAL_HEADERS.mapNotNull { request.headers[it] }

    private companion object {
        const val FOREIGN = "foreign.example.net"
        val CREDENTIAL_HEADERS = listOf(HttpHeaders.Authorization, "PRIVATE-TOKEN", "JOB-TOKEN", "X-Amz-Security-Token")

        fun hosted(
            url: String,
            token: String,
            vararg extra: Pair<String, JsonElement>,
        ) = obj("url" to JsonPrimitive(url), "token" to JsonPrimitive(token), *extra)

        fun obj(vararg entries: Pair<String, JsonElement>) = JsonObject(mapOf(*entries))

        val HOSTED = obj("url" to JsonPrimitive("https://scm.example.com"), "token" to JsonPrimitive("t0ken"))
        val AWS = obj(
            "region" to JsonPrimitive("us-east-1"),
            "accessKeyId" to JsonPrimitive("AKIDEXAMPLE"),
            "secretAccessKey" to JsonPrimitive("secret"),
        )
        val WRONG_TYPED_URL = obj("url" to obj("host" to JsonPrimitive("scm.example.com")))
        val WRONG_TYPED_TOKEN = obj("token" to obj("value" to JsonPrimitive("t0ken")))
        val INSECURE_URL = obj("url" to JsonPrimitive("http://scm.example.com"), "token" to JsonPrimitive("t0ken"))
    }
}
