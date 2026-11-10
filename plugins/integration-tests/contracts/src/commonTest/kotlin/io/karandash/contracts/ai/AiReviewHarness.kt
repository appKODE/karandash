package io.karandash.contracts.ai

import io.karandash.api.Module
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.pipeline.ValidationOptions
import io.karandash.cli.pipeline.ValidationPipeline
import io.karandash.cli.pipeline.ValidationPipelineResult
import io.karandash.contracts.support.CapturingLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.providers.AnthropicProvider
import io.karandash.plugins.ai.providers.OpenAiProvider
import io.karandash.testfixtures.TestFiles
import io.karandash.testfixtures.createTestMergeRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.random.Random

/** One review comment the fake LLM returns. */
@Serializable
data class FakeComment(
    val file: String,
    val line: Int,
    val message: String,
    val existingCode: String? = null,
    val suggestedCode: String? = null,
)

/**
 * A scripted HTTP failure the fake LLM returns instead of a completion.
 *
 * Not @Serializable: test double, holds a non-serializable `HttpStatusCode`.
 */
data class FakeFailure(
    val status: HttpStatusCode,
    val headers: Headers = Headers.Empty,
)

/**
 * End-to-end harness for the AI review path used by the AIR contract tests:
 * YAML config file -> ConfigMapParser -> ValidationPipeline (file mode, real built-in modules)
 * -> real `OpenAiProvider` whose HTTP engine is a Ktor [MockEngine] capturing every request body.
 *
 * One harness instance owns one temp dir (config, MR JSON, cache dir), so consecutive [run] calls
 * share the state cache - required by the changed-config and prior-findings tests.
 *
 * @param provider `openai` or `anthropic`; the matching real provider is bound to the MockEngine and
 *   answers in that provider's wire format (Anthropic: a `thinking` block before the `text` block).
 * @param failures Per-call script: call N (0-based) gets `failures(N, model)` instead of a completion when non-null.
 * @param extraReply Extra top-level keys in every reply (e.g. `suggestions`, `summary`) so other analyzers parse it too.
 * @param extraModules Registered after the built-ins (enrichment providers, context enrichers); same id replaces.
 */
class AiReviewHarness(
    private val comments: List<FakeComment> = listOf(FakeComment("src/A.kt", ADDED_LINE, "Possible null dereference")),
    private val provider: String = PROVIDER_ID,
    private val model: String = MODEL,
    private val maxRetries: Int = 0,
    private val failures: (call: Int, model: String) -> FakeFailure? = { _, _ -> null },
    private val extraReply: JsonObject = JsonObject(emptyMap()),
    private val extraModules: List<Module> = emptyList(),
) {
    val dir: Path = TestFiles.createTestTempDirectory("karandash-air").also {
        SystemFileSystem.createDirectories(it)
    }
    val requestBodies = mutableListOf<String>()
    val requestHeaders = mutableListOf<Headers>()
    val logger = CapturingLogger()

    /** `model` of every request, in call order. */
    val requestModels: List<String> get() = requestBodies.map(::modelOf)

    private val bookkeeping = Mutex()

    private fun modelOf(body: String): String =
        KarandashJson.Default
            .parseToJsonElement(body)
            .jsonObject["model"]
            ?.jsonPrimitive
            ?.content
            .orEmpty()

    private val engine = MockEngine { request ->
        val body = request.body.toByteArray().decodeToString()
        val call = bookkeeping.withLock {
            requestBodies += body
            requestHeaders += request.headers
            requestBodies.size - 1
        }
        val requested = modelOf(body)
        val failure = failures(call, requested)
        if (failure != null) {
            respond("{\"error\":{\"message\":\"scripted ${failure.status.value}\"}}", failure.status, failure.headers)
        } else {
            respond(
                content = if (provider == ANTHROPIC) anthropicJson(requested) else completionJson(requested),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
    }

    /** All captured request bodies joined, for "prompt contains X" assertions. */
    val allRequests: String get() = requestBodies.joinToString("\n----\n")

    /**
     * Runs the full validate pipeline once.
     *
     * @param aiBlockYaml YAML lines nested under `ai:` (already indented by 2 spaces), appended after
     *   the provider/model/mock-endpoint keys and `reviewComments: { enabled: true }`.
     * @param topLevelYaml YAML appended at the top level, after the `ai:` block (e.g. an `enrichment:` section).
     */
    suspend fun run(
        aiBlockYaml: String = "",
        mr: MergeRequest = defaultMr(),
        topLevelYaml: String = "",
    ): ValidationPipelineResult {
        val configPath = Path(dir, "config.yaml")
        write(configPath, configYaml(aiBlockYaml) + topLevelYaml.trimIndent().let { if (it.isBlank()) "" else it + "\n" })
        val mrPath = Path(dir, "mr.json")
        write(mrPath, KarandashJson.Default.encodeToString(MergeRequest.serializer(), mr))

        val bootstrap = PluginBootstrap()
        // Module contexts log through the bootstrap logger; the CLI points it at its own logger the same way.
        bootstrap.logger.configure(logger)
        bootstrap.loadPlugins()
        // Replace the built-in provider (same module id) with one bound to the MockEngine.
        bootstrap.registry().register(
            if (provider == ANTHROPIC) {
                AnthropicProvider(httpClientEngine = engine, maxRetries = maxRetries)
            } else {
                OpenAiProvider(httpClientEngine = engine, maxRetries = maxRetries)
            },
        )
        extraModules.forEach { bootstrap.registry().register(it) }

        val options = ValidationOptions(
            configPathStr = configPath.toString(),
            mrDataStr = mrPath.toString(),
            mrId = null,
            platform = null,
            project = null,
            prUrl = null,
            format = "console",
            outputPathStr = null,
            quiet = true,
            verbose = false,
            strict = false,
            dryRun = false,
            explain = false,
            fix = false,
            yes = false,
            noAutoDetect = true,
            outputWriter = {},
            sharedBootstrap = bootstrap,
            logger = logger,
        )
        return ValidationPipeline(options, sharedBootstrap = bootstrap).execute()
    }

    private fun configYaml(aiBlockYaml: String): String =
        buildString {
            appendLine("cache:")
            appendLine("  dir: \"${Path(dir, "cache")}\"")
            appendLine("ai:")
            appendLine("  provider: $provider")
            appendLine("  apiKey: test-key")
            appendLine("  baseUrl: \"http://mock.invalid/v1\"")
            appendLine("  model: $model")
            // The default .karandash/stats lands in the working directory
            appendLine("  statistics:")
            appendLine("    dir: \"${Path(dir, "stats")}\"")
            appendLine("  reviewComments:")
            appendLine("    enabled: true")
            if (aiBlockYaml.isNotBlank()) append(aiBlockYaml.trimEnd()).append('\n')
        }

    private fun commentsJson(): JsonObject =
        buildJsonObject {
            extraReply.forEach { (key, value) -> put(key, value) }
            put(
                "comments",
                buildJsonArray {
                    comments.forEach { c ->
                        add(
                            buildJsonObject {
                                put("file", c.file)
                                put("line", c.line)
                                put("category", "bug")
                                put("severity", "warning")
                                put("message", c.message)
                                put("confidence", HIGH_CONFIDENCE)
                                c.existingCode?.let { put("existingCode", it) }
                                c.suggestedCode?.let { put("suggestedCode", it) }
                            },
                        )
                    }
                },
            )
        }

    private fun anthropicJson(requested: String): String =
        buildJsonObject {
            put("id", "msg_contract")
            put("type", "message")
            put("role", "assistant")
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "thinking")
                            put("thinking", "Looking at the diff first.")
                        },
                    )
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", commentsJson().toString())
                        },
                    )
                },
            )
            put("model", requested)
            put("stop_reason", "end_turn")
            put(
                "usage",
                buildJsonObject {
                    put("input_tokens", PROMPT_TOKENS)
                    put("output_tokens", COMPLETION_TOKENS)
                },
            )
        }.toString()

    private fun completionJson(requested: String): String {
        val message = buildJsonObject {
            put("role", "assistant")
            put("content", commentsJson().toString())
        }
        return JsonObject(
            mapOf(
                "id" to JsonPrimitive("chatcmpl-contract"),
                "object" to JsonPrimitive("chat.completion"),
                "model" to JsonPrimitive(requested),
                "choices" to JsonArray(
                    listOf(
                        buildJsonObject {
                            put("index", 0)
                            put("message", message)
                            put("finish_reason", "stop")
                        },
                    ),
                ),
                "usage" to buildJsonObject {
                    put("prompt_tokens", PROMPT_TOKENS)
                    put("completion_tokens", COMPLETION_TOKENS)
                    put("total_tokens", PROMPT_TOKENS + COMPLETION_TOKENS)
                },
            ),
        ).toString()
    }

    private fun write(
        path: Path,
        content: String,
    ) {
        SystemFileSystem.sink(path).buffered().use { it.writeString(content) }
    }

    companion object {
        const val MODEL = "contract-model-m"
        const val PROVIDER_ID = "openai"
        const val ANTHROPIC = "anthropic"

        /** New-file line of the first `+` line in [A_KT_DIFF]. */
        const val ADDED_LINE = 12

        /** A context line inside the hunk of [A_KT_DIFF], 6 lines from the nearest `+` line (beyond snap distance 2). */
        const val CONTEXT_LINE = 6

        private const val HIGH_CONFIDENCE = 0.9
        private const val PROMPT_TOKENS = 100
        private const val COMPLETION_TOKENS = 20

        /** Hunk `@@ -5,8 +5,10 @@`: new-file lines 5-11 context, 12-13 added, 14 context. */
        val A_KT_DIFF: String =
            """
            @@ -5,8 +5,10 @@
             package src
             import foo.Alpha
             import foo.Beta
             import foo.Gamma
             import foo.Delta
             import foo.Bar
             fun keep() = Unit
            +fun risky(x: String?) = x!!.length
            +fun other() = Bar()
             fun tail() = Unit
            """.trimIndent()

        fun aKtChange(diff: String = A_KT_DIFF): FileChange =
            FileChange(
                path = "src/A.kt",
                changeType = ChangeType.MODIFIED,
                additions = 2,
                deletions = 0,
                diffContent = diff,
            )

        fun defaultMr(changes: List<FileChange> = listOf(aKtChange())): MergeRequest =
            createTestMergeRequest(title = "AIR contract MR", changes = changes).copy(id = "air-1")

        /** Findings produced by the AI review rule(s) (any rule id containing "review"). */
        fun aiFindings(result: ValidationPipelineResult): List<Finding> = result.validationResult?.findings.orEmpty()
    }
}
