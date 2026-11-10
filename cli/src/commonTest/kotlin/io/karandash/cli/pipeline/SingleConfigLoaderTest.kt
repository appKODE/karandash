package io.karandash.cli.pipeline

import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every command reads its config through [ConfigLoader]: no command source builds the parse pipeline itself, so
 * `extends:`, `{ file: }`, `env:`, missing plugins and profiles apply the same way everywhere.
 */
class SingleConfigLoaderTest {
    private val repoRoot: Path = checkNotNull(TestFiles.findRepoRoot()) { "repo root not found" }

    /** Every `.kt` file under `cli` and `cli-ai` `src/commonMain/kotlin`. */
    private fun commandSources(): List<Path> =
        listOf("cli", "cli-ai").flatMap { ktFiles(Path(repoRoot, it, "src", "commonMain", "kotlin")) }

    private fun ktFiles(dir: Path): List<Path> =
        SystemFileSystem.list(dir).flatMap { path ->
            when {
                SystemFileSystem.metadataOrNull(path)?.isDirectory == true -> ktFiles(path)
                path.name.endsWith(".kt") -> listOf(path)
                else -> emptyList()
            }
        }

    private fun lines(file: Path): List<String> = TestFiles.readText(file).lines()

    /**
     * Entry points each file may use: the loader discovers the file and drives the pipeline, the pipeline parses,
     * and `validate-extends` reports the chain the pipeline merges, so it drives the pipeline but builds nothing itself.
     */
    private val allowed = mapOf(
        "ConfigLoader.kt" to setOf("ValidateConfigPipeline(", "ConfigDiscovery("),
        "ValidateConfigPipeline.kt" to setOf("ValidateConfigPipeline(", "ConfigMapParser.", "ConfigChainParser(", "ConfigChainResolver("),
        "ValidateExtendsCommand.kt" to setOf("ValidateConfigPipeline("),
    )

    /** Building the parse pipeline, discovering the file, or a second whole-config parse entry point. */
    private val pipelineEntryPoints =
        listOf(
            "ValidateConfigPipeline(",
            "ConfigMapParser.",
            "ConfigChainParser(",
            "ConfigChainResolver(",
            "ConfigDiscovery(",
            "fun parseFullConfig",
            "fun parseConfigFile",
        )

    @Test
    fun `no command source parses the config outside the loader`() {
        val sources = commandSources()
        assertTrue(sources.size > 10, "command sources not found under $repoRoot")
        val offenders = sources
            .flatMap { file ->
                lines(file)
                    .withIndex()
                    .filter { (_, line) ->
                        pipelineEntryPoints.any { it in line && it !in allowed[file.name].orEmpty() }
                    }.map { (index, line) -> "${file.toString().removePrefix("$repoRoot/")}:${index + 1}: ${line.trim()}" }
            }
        assertEquals(emptyList(), offenders, "load the config with ConfigLoader.forCommand instead")
    }

    @Test
    fun `the manifest is read in one place`() {
        val readers = commandSources()
            .filter { file -> lines(file).any { "ManifestReader.parseManifest" in it } }
            .map { it.name }
        assertEquals(listOf("PluginManifestSettings.kt"), readers, "read the manifest with readPluginManifest instead")
    }
}
