package io.karandash.cli.pipeline

import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.dangerfile.DangerfileRunner
import io.karandash.cli.dangerfile.DangerfileType
import io.karandash.cli.dangerfile.DetectedDangerfile
import io.karandash.logger.Logger
import io.karandash.platform.PlatformProcess
import io.karandash.sdk.NoOpLogger
import io.karandash.testfixtures.TestFiles
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** A JS Dangerfile runs through the node Danger DSL bridge, so `danger.git.modified_files` is populated. */
class JsDangerfileTest {
    private fun nodeAvailable(): Boolean =
        try {
            PlatformProcess.execute(listOf("node", "--version")).isSuccess
        } catch (ignored: IOException) {
            false
        }

    private fun write(
        path: Path,
        text: String,
    ) {
        SystemFileSystem.sink(path).buffered().use { it.writeString(text) }
    }

    @Test
    fun `js Dangerfile sees modified files from the MR`() =
        runTest(timeout = 5.minutes) {
            if (!nodeAvailable()) return@runTest
            val dir = Path(SystemFileSystem.resolve(Path(".")), "cli/build/tmp/dangerfile")
            SystemFileSystem.createDirectories(dir)
            val config = Path(dir, "config.yaml")
            write(config, "ai:\n  enabled: false\n  statistics:\n    dir: cli/build/tmp/stats\n")
            write(
                Path(dir, "Dangerfile.js"),
                "if (danger.git.modified_files.length > 0) warn('modified: ' + danger.git.modified_files[0]);\n",
            )
            val bootstrap = PluginBootstrap(envReader = { null })
            bootstrap.loadPluginsFromDirs(emptyList())
            val options = ValidationOptions(
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
            val messages = try {
                ValidationPipeline(options, bootstrap)
                    .execute()
                    .validationResult
                    ?.findings
                    .orEmpty()
                    .map { it.message }
            } finally {
                bootstrap.shutdown()
            }
            assertTrue("modified: src/main.kt" in messages, "Dangerfile finding missing: $messages")
        }

    private class Warnings : Logger {
        val warns = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warns += message
        }

        override fun error(message: String) = Unit
    }

    private suspend fun runDangerfile(
        script: String,
        timeout: Duration,
        logger: Logger,
    ): Int {
        val dir = TestFiles.createTestTempDirectory("dangerfile-runner")
        val dangerfile = Path(dir, "Dangerfile.js")
        write(dangerfile, script)
        return DangerfileRunner(dir.toString(), logger, timeout)
            .run(DetectedDangerfile(dangerfile.toString(), DangerfileType.JS), createTestMergeRequest())
            .size
    }

    @Test
    fun `a hanging Dangerfile is killed at its timeout with a warning`() =
        runTest(timeout = 1.minutes) {
            if (!nodeAvailable()) return@runTest
            val logger = Warnings()
            val started = TimeSource.Monotonic.markNow()

            val findings = runDangerfile("while (true) {}\n", 2.seconds, logger)

            assertEquals(0, findings)
            assertTrue(started.elapsedNow() < 30.seconds, "took ${started.elapsedNow()}")
            assertTrue(logger.warns.any { "timeouts.dangerfile" in it }, "${logger.warns}")
        }

    @Test
    fun `unparsable Dangerfile results log a warning`() =
        runTest(timeout = 1.minutes) {
            if (!nodeAvailable()) return@runTest
            val logger = Warnings()
            val script =
                """
                const out = require('path').join(require('os').tmpdir(), 'karandash-notjson-' + process.pid + '.json');
                require('fs').writeFileSync(out, 'not json');
                console.log('danger-results:/' + out);
                """.trimIndent()

            assertEquals(0, runDangerfile(script, 1.minutes, logger))
            assertTrue(logger.warns.any { "not valid Danger results" in it }, "${logger.warns}")
        }

    @Test
    fun `a Dangerfile that fails before the sentinel logs its exit`() =
        runTest(timeout = 1.minutes) {
            if (!nodeAvailable()) return@runTest
            val logger = Warnings()

            assertEquals(0, runDangerfile("throw new Error('boom')\n", 1.minutes, logger))
            assertTrue(logger.warns.any { "exited 2" in it }, "${logger.warns}")
            assertTrue(logger.warns.any { "no 'danger-results" in it }, "${logger.warns}")
        }

    private companion object {
        const val MR = "tools/integration-tests/fixtures/test-mr.json"
    }
}
