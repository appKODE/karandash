package io.karandash.contracts.support

import io.karandash.api.Module
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.pipeline.ValidationOptions
import io.karandash.cli.pipeline.ValidationPipeline
import io.karandash.cli.pipeline.ValidationPipelineResult
import io.karandash.models.MergeRequest
import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import java.io.ByteArrayOutputStream
import java.io.PrintStream

// JVM-only: StdoutOutputModule prints with `println`, and only the JVM can swap `System.out` to capture it.

/** Outcome of one [runFwPipeline] call: the pipeline result (or its failure), captured stdout and logger. */
class FwPipelineRun(
    val result: Result<ValidationPipelineResult>,
    val stdout: String,
    val logger: CapturingLogger,
)

/**
 * Runs the real `validate` pipeline once in file mode (YAML config file + MR JSON file in [dir]),
 * with built-in modules plus [extraModules], capturing `System.out` (StdoutOutputModule uses `println`).
 * [tweak] adjusts the options, e.g. to switch to live mode against [FwFakePlatformService].
 */
suspend fun runFwPipeline(
    dir: Path,
    configYaml: String,
    extraModules: List<Module> = emptyList(),
    mr: MergeRequest = createTestMergeRequest(),
    tweak: (ValidationOptions) -> ValidationOptions = { it },
): FwPipelineRun {
    val configPath = Path(dir, "config.yaml")
    writeFwFile(configPath, "cache:\n  dir: ${Path(dir, "cache")}\n" + configYaml.trimIndent() + "\n")
    val mrPath = Path(dir, "mr.json")
    writeFwFile(mrPath, KarandashJson.Default.encodeToString(MergeRequest.serializer(), mr))

    val bootstrap = PluginBootstrap()
    bootstrap.loadPluginsFromDirs(emptyList())
    extraModules.forEach { bootstrap.registry().register(it) }

    val logger = CapturingLogger()
    val options = ValidationOptions(
        configPathStr = configPath.toString(),
        mrDataStr = mrPath.toString(),
        mrId = null,
        platform = null,
        project = null,
        prUrl = null,
        format = "console",
        outputPathStr = null,
        quiet = false,
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
    val original = System.out
    val buffer = ByteArrayOutputStream()
    System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
    val result = try {
        runCatching { ValidationPipeline(tweak(options), sharedBootstrap = bootstrap).execute() }
    } finally {
        System.setOut(original)
    }
    return FwPipelineRun(result, buffer.toString(Charsets.UTF_8), logger)
}

private fun writeFwFile(
    path: Path,
    content: String,
) {
    SystemFileSystem.sink(path).buffered().use { it.writeString(content) }
}
