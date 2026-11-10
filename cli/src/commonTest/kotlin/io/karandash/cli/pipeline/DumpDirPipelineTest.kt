package io.karandash.cli.pipeline

import io.karandash.api.PluginContext
import io.karandash.api.module.RuleModule
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.TestFileHelpers
import io.karandash.logger.NoOpLogger
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** `validate --dump-dir` saves each layer of a run, numbered in the order the run reached it. */
class DumpDirPipelineTest {
    private class FixedRule : RuleModule {
        override val id = "dump-dir-rule-module"
        override val name = id
        override val version = "1.0"

        override suspend fun contributedRules(): Set<String> = setOf(RULE)

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ): List<Finding> = listOf(Finding(Severity.WARNING, "dumped", RULE))
    }

    @Test
    fun `a run dumps config MR validation and result files in order`() =
        runTest(timeout = 5.minutes) {
            val dir = TestFileHelpers.createTempDir("dump-run")
            val bootstrap = PluginBootstrap(envReader = { null })
            try {
                val config = Path(dir, "config.yaml")
                TestFileHelpers.writeFile(
                    config,
                    "ai:\n  enabled: false\n  statistics:\n    dir: ${Path(dir, "stats")}\nrules:\n  $RULE: {}\n"
                )
                bootstrap.loadPluginsFromDirs(emptyList())
                bootstrap.registry().register(FixedRule())
                val dumpDir = Path(dir, "dump")
                ValidationPipeline(options(config.toString(), dumpDir.toString(), bootstrap), bootstrap).execute()

                assertEquals("*\n", TestFileHelpers.readFile(Path(dumpDir, ".gitignore")))
                val files = dumpedFiles(dumpDir, "")
                val order = files
                    .sortedBy { it.substringAfterLast('/').substringBefore('-').toInt() }
                    .map {
                        it.substringBeforeLast('/', "").let { d -> if (d.isEmpty()) "" else "$d/" } +
                            it.substringAfterLast('/').substringAfter('-')
                    }
                val expected = listOf(
                    "config/raw.json",
                    "config/effective.json",
                    "mr-fetched.json",
                    "validation-result.json",
                    "report-findings.json",
                    "result.json",
                )
                val positions = expected.map { name -> order.indexOf(name).also { assertTrue(it >= 0, "$name missing in $order") } }
                assertEquals(positions.sorted(), positions, "dump order: $order")
                val result = files.single { it.substringAfter('-') == "result.json" }
                assertTrue("\"exitCode\"" in TestFileHelpers.readFile(Path(dumpDir, result)))
                assertTrue(
                    "dumped" in TestFileHelpers.readFile(Path(dumpDir, files.single { it.substringAfter('-') == "validation-result.json" }))
                )
            } finally {
                bootstrap.shutdown()
                TestFileHelpers.deleteTempDir(dir)
            }
        }

    /** Paths relative to [root] of every dumped file, `.gitignore` excluded. */
    private fun dumpedFiles(
        root: Path,
        relative: String,
    ): List<String> =
        SystemFileSystem.list(if (relative.isEmpty()) root else Path(root, relative)).flatMap { child ->
            val name = if (relative.isEmpty()) child.name else "$relative/${child.name}"
            when {
                SystemFileSystem.metadataOrNull(child)?.isDirectory == true -> dumpedFiles(root, name)
                child.name == ".gitignore" -> emptyList()
                else -> listOf(name)
            }
        }

    private fun options(
        config: String,
        dumpDir: String,
        bootstrap: PluginBootstrap,
    ) = ValidationOptions(
        configPathStr = config,
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
        dumpDir = dumpDir,
        logger = NoOpLogger,
    )

    private companion object {
        const val MR = "tools/integration-tests/fixtures/test-mr.json"
        const val RULE = "dump-dir-rule"
    }
}
