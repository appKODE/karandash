package io.karandash.lsp

import io.karandash.cli.commands.config.ConfigCheck
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import org.eclipse.lsp4j.DiagnosticSeverity
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The LSP reports what `karandash config validate` reports for the open buffer, with the same text. */
class ConfigPipelineDiagnosticsTest {
    private val bootstrap = TestRegistryFactory.loadedBootstrap()
    private val registry = bootstrap.registry()
    private val provider = DiagnosticProvider(CachedRegistryMetadata(registry), ConfigPipelineDiagnostics(bootstrap))
    private val dir = java.nio.file.Path
        .of(TestFiles.createTestTempDirectory("karandash-lsp-pipeline").toString())

    private suspend fun validate(input: String): List<ConfigCheck.Message> =
        ConfigCheck(bootstrap).check(Path(CONFIG_FILE), readFile = { input }).messages

    @Test
    fun `a config error validate reports is reported identically by LSP diagnostics`() =
        runTest {
            val input = "extends = \"missing-parent.yaml\""
            val expected = validate(input).filter { it.severity == ConfigCheck.Severity.ERROR }.map { it.text }

            val errors = provider.computeDiagnostics(input).filter { it.severity == DiagnosticSeverity.Error }

            assertTrue(expected.isNotEmpty(), "fixture must fail config validate")
            assertEquals(expected, errors.map { it.message.left })
        }

    @Test
    fun `a schema warning validate logs is reported identically by LSP diagnostics on its line`() =
        runTest {
            val input = "rules {\n    titleFormat = \"x\"\n}"
            val expected = validate(input).map { it.text }.filter { it.startsWith("[SCHEMA]") }

            val warnings = provider.computeDiagnostics(input).filter { it.severity == DiagnosticSeverity.Warning }

            assertTrue(expected.isNotEmpty(), "fixture must trigger a schema warning")
            assertEquals(expected, warnings.map { it.message.left }.filter { it.startsWith("[SCHEMA]") })
            assertEquals(
                1,
                warnings
                    .first { it.message.left == expected.first() }
                    .range.start.line
            )
        }

    @Test
    fun `an unsaved buffer is checked instead of the file on disk`() {
        val uri = dir.resolve(CONFIG_FILE).toUri().toString()
        Files.writeString(dir.resolve(CONFIG_FILE), "rules {\n    titleFormat = \"x\"\n}")

        val diagnostics = ConfigPipelineDiagnostics(
            bootstrap
        ).diagnose("rules {\n    titleFormat {\n        pattern = \"^feat\"\n    }\n}", uri)

        assertEquals(emptyList(), diagnostics)
    }

    @Test
    fun `a valid config gets no pipeline diagnostics`() {
        val input = "rules {\n    titleFormat {\n        pattern = \"^feat\"\n    }\n}"

        assertEquals(emptyList(), ConfigPipelineDiagnostics(bootstrap).diagnose(input))
    }

    private companion object {
        const val CONFIG_FILE = "config.kts"
    }
}
