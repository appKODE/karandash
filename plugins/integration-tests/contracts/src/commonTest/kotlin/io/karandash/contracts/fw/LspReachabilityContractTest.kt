package io.karandash.contracts.fw

import io.karandash.contracts.support.listKotlinFiles
import io.karandash.contracts.support.readTextFile
import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The LSP server is unreachable for users.
 *
 * - The VS Code extension builds `:lsp-server:shadowJar`, but the project is `:dsl:lsp-server`
 *   (`settings.gradle.kts:60`) - `tools/ide-extensions/vscode-extension/src/extension.ts:143,164-165`.
 * - No `lsp` subcommand is registered on the root CLI - `KarandashCli.kt` `executeMain`.
 *
 * Paths are relative to the repo root, found from the working dir (`contractTest` runs in the root, native in the module).
 */
class LspReachabilityContractTest {
    private val repoRoot = checkNotNull(TestFiles.findRepoRoot()) { "no settings.gradle.kts above the working dir" }

    @Test
    fun `VS Code extension builds the dsl lsp-server project`() {
        val source = readTextFile(Path(repoRoot, "tools/ide-extensions/vscode-extension/src/extension.ts"))

        assertTrue(
            source.contains(":dsl:lsp-server:shadowJar"),
            "extension.ts never references ':dsl:lsp-server:shadowJar'",
        )
        assertFalse(
            source.contains("./gradlew :lsp-server:shadowJar"),
            "extension.ts still runs the non-existent ':lsp-server:shadowJar' task",
        )
    }

    @Test
    fun `root CLI registers an lsp subcommand`() {
        val lspMarkers = listOf("name = \"lsp\"", "CliktCommand(\"lsp\")")
        val sources = listKotlinFiles(Path(repoRoot, "cli/src")) + listKotlinFiles(Path(repoRoot, "cli-app/src"))

        assertTrue(sources.isNotEmpty(), "no CLI sources found; working dir is not the repo root")
        assertTrue(
            sources.any { file -> readTextFile(file).let { text -> lspMarkers.any { it in text } } },
            "no CLI command named 'lsp' in cli/ or cli-app/ sources",
        )
    }
}
