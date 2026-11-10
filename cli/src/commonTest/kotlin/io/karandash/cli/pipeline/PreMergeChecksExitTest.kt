package io.karandash.cli.pipeline

import com.github.ajalt.clikt.testing.test
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiProviderModule
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.validate.ValidateCommand
import io.karandash.cli.errors.ExitCodes
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** `ai.checks` end to end with a fake provider: a failing `mode: error` check fails the run, a failing `warn` one does not. */
class PreMergeChecksExitTest {
    /** Answers the checklist call with a failing check and every other AI call with no findings. */
    private class ChecksProvider(
        private val prompts: MutableList<String>,
    ) : AiProviderModule {
        override val id = "pre-merge-checks-provider"
        override val name = "Pre-merge checks provider"
        override val version = "0.0.0"
        override val providerId = "custom"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun complete(request: AiCompletionRequest): AiCompletionResponse {
            val checklist = request.systemPrompt.orEmpty().contains("pre-merge checks")
            if (checklist) prompts += request.prompt
            val content = if (checklist) """{"results":[{"index":0,"status":"fail","reason":"token printed"}]}""" else "[]"
            return AiCompletionResponse(content = content, promptTokens = 1, completionTokens = 1, totalTokens = 2, model = request.model)
        }
    }

    private suspend fun exitWith(mode: String): Pair<Int, List<String>> {
        val prompts = mutableListOf<String>()
        val workDir = TestFiles.createTestTempDirectory("pre-merge-checks")
        val config = Path(workDir, "config.yaml")
        TestFiles.writeText(
            config,
            """
            cache:
              dir: "${Path(workDir, "cache")}"
            ai:
              provider: "custom"
              api-key: "k"
              model: "m"
              statistics:
                dir: "${Path(workDir, "stats")}"
              checks:
                - name: No secrets logged
                  instruction: The change does not log tokens or passwords
                  mode: $mode
            """.trimIndent(),
        )
        val bootstrap = PluginBootstrap(envReader = { null })
        bootstrap.loadPluginsFromDirs(emptyList())
        bootstrap.registry().register(ChecksProvider(prompts))
        try {
            val args = listOf("--no-auto-detect", "--no-cache", "--config", config.toString(), "--mr", MR, "--format", "json")
            return ValidateCommand(bootstrap).test(args).statusCode to prompts.toList()
        } finally {
            bootstrap.shutdown()
        }
    }

    @Test
    fun `a failing error check fails the run`() =
        runTest(timeout = 5.minutes) {
            val (exit, prompts) = exitWith("error")

            assertEquals(ExitCodes.VALIDATION_FAILED, exit)
            assertTrue(prompts.single().contains("The change does not log tokens or passwords"))
        }

    @Test
    fun `a failing warn check does not fail the run`() =
        runTest(timeout = 5.minutes) {
            assertEquals(0, exitWith("warn").first)
        }

    private companion object {
        const val MR = "tools/integration-tests/fixtures/ai-mr.json"
    }
}
