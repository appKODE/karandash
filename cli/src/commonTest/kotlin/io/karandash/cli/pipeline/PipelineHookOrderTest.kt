@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.cli.pipeline

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.module.PipelineEnvironment
import io.karandash.api.module.PipelineExtension
import io.karandash.api.platform.FetchOptions
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.TestFileHelpers
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.sdk.NoOpLogger
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/** The pipeline calls each [PipelineExtension] hook in the order the SDK documents (docs/development/conventions.md Validation Flow). */
class PipelineHookOrderTest {
    private class RecordingExtension : PipelineExtension {
        val calls = mutableListOf<String>()
        override val id = "hook-order-extension"
        override val name = "Hook order extension"
        override val version = "1.0"
        override val type = ModuleType.PIPELINE_EXTENSION

        override suspend fun initialize(context: PluginContext): Boolean = true

        override fun fetchOptions(config: JsonElement?): FetchOptions? = null.also { calls += "fetchOptions" }

        override fun needsBlame(config: JsonElement?): Boolean = false.also { calls += "needsBlame" }

        override suspend fun onConfigured(env: PipelineEnvironment) {
            calls += "onConfigured"
        }

        override fun baseFiles(config: JsonElement?): Set<String> = setOf("README.md")

        override suspend fun onBaseFiles(files: Map<String, String>) {
            calls += "onBaseFiles"
        }

        override suspend fun onPostFetch(
            mr: MergeRequest,
            config: JsonElement?,
        ) {
            calls += "onPostFetch"
        }

        override suspend fun afterValidation(
            findings: List<Finding>,
            mr: MergeRequest,
            config: JsonElement?,
        ): List<Finding> = findings.also { calls += "afterValidation" }

        override fun saveIncrementalState(
            mr: MergeRequest,
            findings: List<Finding>,
        ) {
            calls += "saveIncrementalState"
        }

        override fun incrementalChangedFiles(): Set<String>? = null.also { calls += "incrementalChangedFiles" }

        override fun incrementalCachedFindings(): List<Finding>? = null.also { calls += "incrementalCachedFindings" }

        override suspend fun beforeReporting(
            findings: List<Finding>,
            mr: MergeRequest,
        ): List<Finding> = findings.also { calls += "beforeReporting" }

        override suspend fun onPipelineComplete() {
            calls += "onPipelineComplete"
        }
    }

    @Test
    fun `a validate run calls every extension hook once in the documented order`() =
        runTest(timeout = 5.minutes) {
            val config = Path("cli/build/tmp/hook-order.yaml")
            SystemFileSystem.createDirectories(checkNotNull(config.parent))
            val cacheDir = TestFileHelpers.createTempDir("hook-order-cache")
            SystemFileSystem.sink(config).buffered().use {
                it.writeString("ai:\n  statistics:\n    dir: cli/build/tmp/stats\ncache:\n  dir: $cacheDir\n")
            }
            val extension = RecordingExtension()
            val bootstrap = PluginBootstrap(envReader = { null })
            bootstrap.loadPluginsFromDirs(emptyList())
            // fetchOptions and incrementalChangedFiles ask extensions until one answers: only this one may answer
            bootstrap.registry().pipelineExtensions().forEach { bootstrap.registry().unregister(it.id) }
            bootstrap.registry().register(extension)
            val options =
                ValidationOptions(
                    configPathStr = config.toString(),
                    mrDataStr = MR,
                    mrId = null,
                    platform = null,
                    project = null,
                    prUrl = null,
                    format = "json",
                    explicitFormat = true,
                    outputPathStr = null,
                    quiet = true,
                    verbose = false,
                    strict = false,
                    dryRun = false,
                    explain = false,
                    fix = false,
                    yes = false,
                    noAutoDetect = true,
                    outputWriter = { },
                    sharedBootstrap = bootstrap,
                    logger = NoOpLogger,
                )
            try {
                ValidationPipeline(options, bootstrap).execute()
            } finally {
                bootstrap.shutdown()
            }

            assertEquals(EXPECTED_ORDER, extension.calls)
        }

    private companion object {
        const val MR = "tools/integration-tests/fixtures/test-mr.json"
        val EXPECTED_ORDER =
            listOf(
                "fetchOptions",
                "needsBlame",
                "onConfigured",
                "onBaseFiles",
                "onPostFetch",
                "afterValidation",
                "saveIncrementalState",
                "incrementalChangedFiles",
                "incrementalCachedFindings",
                "beforeReporting",
                "onPipelineComplete",
            )
    }
}
