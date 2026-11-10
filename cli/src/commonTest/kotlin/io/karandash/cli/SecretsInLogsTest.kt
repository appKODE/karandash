package io.karandash.cli

import io.karandash.ai.pipeline.AiServiceImpl
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiProviderModule
import io.karandash.bootstrap.CliLogger
import io.karandash.bootstrap.DelegatingLogger
import io.karandash.http.ProviderHttpClientFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Exit gate (OBS 1-2): tokens, API keys and URL credentials fed through every logging path
 * (HTTP plugin, AI response debug, direct/contextual logger calls) never reach verbose output.
 */
class SecretsInLogsTest {
    private val captured = mutableListOf<String>()
    private val logger = CliLogger(verbose = true, sink = captured::add)

    private fun assertNoSecrets() {
        val output = captured.joinToString("\n")
        assertTrue(captured.isNotEmpty(), "nothing was logged — test is not exercising the paths")
        for (secret in SECRETS) {
            assertTrue(secret !in output, "secret '$secret' leaked into logs:\n$output")
        }
    }

    @Test
    fun `a warning at the normal level is redacted too`() {
        val lines = mutableListOf<String>()
        CliLogger(verbose = false, sink = lines::add).warn("fetch https://user:tok@host/x failed; Authorization: Bearer abc")
        val line = lines.single()
        assertTrue("user:tok" !in line && "Bearer abc" !in line, line)
        assertTrue("[WARN] fetch https://" in line, line)
    }

    @Test
    fun httpRequestUrlsAndErrorsAreRedacted() =
        runTest {
            val ok = ProviderHttpClientFactory.create(
                engine = MockEngine { respond("ok", HttpStatusCode.OK) },
                logger = logger,
            )
            ok.get("https://alice:urlpass1@config.example.com/base.yaml").bodyAsText()
            ok.get("https://gitlab.example.com/api/v4/projects?private_token=querytok2").bodyAsText()
            ok.get("https://hooks.slack.com/services/T0/B0/slackhook3").bodyAsText()
            ok.close()

            val failing = ProviderHttpClientFactory.create(
                engine = MockEngine { throw IOException("connect https://bob:errpass4@h/x?key=errkey5 failed") },
                logger = logger,
            )
            assertFails { failing.get("https://h/x").bodyAsText() }
            failing.close()

            assertNoSecrets()
        }

    @Test
    fun aiResponseContentIsRedacted() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(echoingProvider("api_key=aikey6 and Bearer aibearer7"))
            AiServiceImpl(registry, debugLogger = logger::debug)
                .complete(AiCompletionRequest(prompt = "p", model = "m"))
            assertNoSecrets()
        }

    @Test
    fun directAndContextualLogLinesAreRedacted() {
        val delegating = DelegatingLogger()
        val contextual = delegating.withContext("module", "gitlab")
        delegating.configure(logger)

        contextual.debug("config: {\"apiKey\": \"jsonkey8\"}")
        contextual.warn("auth failed for token: tok9")
        logger.error("export GITLAB_TOKEN=glpat-abcdefghijklmnopqrst10")
        logger.info("webhook https://discord.com/api/webhooks/1/discordtok11")
        logger.error("fetch failed", IOException("GET https://u:thrpass12@h/x?access_token=thrtok13"))

        assertNoSecrets()
        assertTrue(captured.any { "[module=gitlab]" in it }, "context dropped: $captured")
    }

    private fun echoingProvider(content: String): AiProviderModule =
        object : AiProviderModule {
            override val id = "provider-test"
            override val name = "Test"
            override val version = "1.0"
            override val providerId = "test"

            override suspend fun initialize(context: PluginContext) = true

            override suspend fun complete(request: AiCompletionRequest) =
                AiCompletionResponse(content, promptTokens = 1, completionTokens = 1, totalTokens = 2, model = "m")
        }

    private companion object {
        val SECRETS = listOf(
            "urlpass1",
            "querytok2",
            "slackhook3",
            "errpass4",
            "errkey5",
            "aikey6",
            "aibearer7",
            "jsonkey8",
            "tok9",
            "glpat-abcdefghijklmnopqrst10",
            "discordtok11",
            "thrpass12",
            "thrtok13",
        )
    }
}
