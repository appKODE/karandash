package io.karandash.cli.compat

import com.github.ajalt.clikt.testing.test
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiProviderModule
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.validate.ValidateCommand
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.outputs.PlatformOutputModule
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Freezes what a live-mode `validate` run sends out: every non-GET request to the GitLab API (posted comments,
 * discussions, label updates) and every LLM request.
 *
 * The production HTTP stack has no engine-injection hook reachable from the CLI, so instead of Ktor `MockEngine` a
 * loopback JDK [HttpServer] plays GitLab and the run uses `--allow-localhost`. LLM traffic cannot be pointed at the
 * loopback server in v1.46.0 without environment variables (`AiServiceImpl` re-resolves the provider by id from the
 * registry and initializes it against the real process environment; `OpenAiProvider` ignores `ai.base-url`), so the
 * LLM side is recorded one layer up: a [RecordingProvider] registered as provider `custom` captures every
 * [AiCompletionRequest] the AI rules send. Nothing leaves the machine.
 */
class CompatLiveSnapshotTest {
    @Test
    fun `live run posts the same payloads and LLM requests`() =
        runTest(timeout = 5.minutes) {
            val recorded = Collections.synchronizedList(mutableListOf<String>())
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange -> handle(exchange, recorded) }
            server.start()
            val (exit, _, stderr) = try {
                val workDir = java.nio.file.Paths
                    .get(TestFiles.createTestTempDirectory("compat-live").toString())
                val config = workDir.resolve("config.yaml")
                // Fresh cache dir per run: persisted AI state from a previous run would skip the LLM call.
                // The review-flow.md artifact goes to the temp dir too, never into the repo the test runs from.
                config.writeText(liveConfig("http://127.0.0.1:${server.address.port}", workDir.toString()))
                val bootstrap = PluginBootstrap(envReader = { null })
                bootstrap.loadPluginsFromDirs(emptyList())
                bootstrap.registry().register(RecordingProvider(recorded))
                // A fixed clock keeps the summary's "updated" time out of the snapshot.
                bootstrap.registry().register(PlatformOutputModule(FIXED_CLOCK))
                ConsoleCapture.capture {
                    try {
                        ValidateCommand(bootstrap)
                            .test(
                                listOf(
                                    "--no-auto-detect",
                                    "--no-cache",
                                    "--allow-localhost",
                                    "--config",
                                    config.toString(),
                                    "--platform",
                                    "gitlab",
                                    "--project",
                                    "group/repo",
                                    "--mr-id",
                                    "1",
                                ),
                            ).statusCode
                    } finally {
                        bootstrap.shutdown()
                    }
                }
            } finally {
                server.stop(0)
            }

            val snapshot = "exit=$exit\n" + recorded.joinToString("\n") { Golden.normalize(it) }
            Golden.assertAllMatch(listOfNotNull(Golden.check("output/live-requests.txt", snapshot)))
            // The AI cost summary is an info line: visible without --verbose.
            assertContains(stderr, "AI plugin cost estimate:")
        }

    private fun handle(
        exchange: HttpExchange,
        recorded: MutableList<String>,
    ) {
        val method = exchange.requestMethod
        val path = exchange.requestURI.rawPath
        val body = exchange.requestBody.readBytes().decodeToString()
        if (method != "GET") recorded += "## $method $path\n$body"
        val response = when {
            method != "GET" -> CREATED_NOTE
            path == "/api/v4/user" -> BOT_USER
            path.endsWith("/merge_requests/1") -> MR
            path.endsWith("/changes") -> CHANGES
            path.endsWith("/commits") -> COMMITS
            else -> "[]"
        }.encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(if (method == "POST") HTTP_CREATED else HTTP_OK, response.size.toLong())
        exchange.responseBody.use { it.write(response) }
    }

    /** Stands in for the LLM endpoint: records each request and answers "no findings". */
    private class RecordingProvider(
        private val recorded: MutableList<String>,
    ) : AiProviderModule {
        override val id = "compat-recording-provider"
        override val name = "Compat recording provider"
        override val version = "0.0.0"
        override val providerId = "custom"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun complete(request: AiCompletionRequest): AiCompletionResponse {
            recorded += "## LLM ${request.model}\n" + KarandashJson.Default.encodeToString(AiCompletionRequest.serializer(), request)
            return AiCompletionResponse(content = "[]", promptTokens = 1, completionTokens = 1, totalTokens = 2, model = request.model)
        }
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201

        val FIXED_CLOCK = object : Clock {
            override fun now(): Instant = Instant.parse("2026-10-30T15:40:00Z")
        }

        fun liveConfig(
            baseUrl: String,
            workDir: String,
        ) = """
            cache:
              dir: "$workDir/cache"
            reporters:
              markdown:
                flow-artifact-dir: "$workDir/debug"
            platforms:
              gitlab:
                url: "$baseUrl"
                token: "compat-token"
            rules:
              title-format:
                pattern: "^JIRA-[0-9]+: .+"
              labels:
                required-labels: ["needs-review"]
              risk-scoring: {}
            ai:
              provider: "custom"
              base-url: "$baseUrl/v1"
              api-key: "compat-key"
              model: "compat-model"
              statistics:
                dir: "$workDir/stats"
            """.trimIndent()

        const val USER = """{"id": 7, "username": "dev", "name": "Dev"}"""
        const val BOT_USER = """{"id": 99, "username": "karandash-bot", "name": "Karandash Bot"}"""
        val MR =
            """
            {"id": 101, "iid": 1, "title": "Add compat feature", "description": "Compat snapshot MR.",
             "source_branch": "feature/compat", "target_branch": "main", "author": $USER, "state": "opened",
             "labels": ["test"], "diff_refs": {"base_sha": "b0", "head_sha": "h0", "start_sha": "s0"}}
            """.trimIndent()
        val CHANGES =
            """
            {"changes": [{"old_path": "src/main.kt", "new_path": "src/main.kt", "new_file": false,
             "renamed_file": false, "deleted_file": false,
             "diff": "@@ -1,2 +1,3 @@\n fun main() {\n+    println(\"hi\")\n }\n"}]}
            """.trimIndent()
        val COMMITS =
            """
            [{"id": "abc123def456abc123def456abc123def456abc1", "short_id": "abc123d", "title": "Add compat feature",
              "message": "Add compat feature", "author_name": "Dev", "author_email": "dev@example.com",
              "created_at": "2026-01-15T10:00:00Z"}]
            """.trimIndent()
        const val CREATED_NOTE = """{"id": 1, "body": "", "notes": []}"""
    }
}
