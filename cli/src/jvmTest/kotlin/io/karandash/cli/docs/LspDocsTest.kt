package io.karandash.cli.docs

import io.karandash.plugins.ai.core.AiPluginConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The VS Code extension launches `karandash lsp` (the one delivery path the
 * release ships), does not promise a bundled jar it does not contain, matches the real config file name and has a CI build.
 */
class LspDocsTest {
    private val dir = "tools/ide-extensions/vscode-extension"
    private val manifest = DocsSupport.read("$dir/package.json")
    private val source = DocsSupport.read("$dir/src/extension.ts")

    @Test
    fun `no setting promises a bundled server the extension does not ship`() {
        assertFalse(manifest.contains("defaults to bundled version"), "package.json promises a bundled jar")
        assertFalse(source.contains("for future releases"), "extension.ts searches a bundled path nothing fills")
    }

    @Test
    fun `extension launches the karandash lsp command`() {
        assertTrue(source.contains("'lsp'"), "extension.ts must start the server with the lsp subcommand")
        assertTrue(manifest.contains("karandash.cliPath"), "package.json needs the karandash.cliPath setting")
    }

    @Test
    fun `document selector and file patterns cover the discovered config file`() {
        assertTrue(manifest.contains("**/.karandash/config.kts"), "the discovered .karandash/config.kts must be listed")
        assertTrue(source.contains("**/.karandash/config.kts"), "the file watcher must watch .karandash/config.kts")
    }

    @Test
    fun `settings never reach a shell and are machine scoped`() {
        assertFalse(source.contains("execSync"), "extension.ts must spawn without a shell (execFileSync)")
        listOf("cliPath", "javaPath", "lspServerPath").forEach {
            val block = manifest.substringAfter("\"karandash.$it\": {").substringBefore("}")
            assertTrue(block.contains("\"scope\": \"machine\""), "karandash.$it must be machine scoped so a workspace cannot set it")
        }
    }

    @Test
    fun `extension probes the lsp command and shows stderr`() {
        assertTrue(source.contains("['lsp', '--help']"), "extension.ts must probe that the binary serves lsp")
        assertTrue(source.contains("stderr"), "extension.ts must surface the probe's stderr")
    }

    @Test
    fun `CI compiles the extension`() {
        val ci = DocsSupport.read(".github/workflows/ci.yml")
        assertTrue(ci.contains(dir) && ci.contains("npm run compile"), "no CI job builds the VS Code extension")
    }

    /** the `ai` hover names real [AiPluginConfig] keys, a drift here fails instead of misleading editors. */
    @Test
    fun `ai hover lists real AI config keys`() {
        val descriptor = AiPluginConfig.serializer().descriptor
        val keys = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.toSet()
        assertTrue(keys.isNotEmpty(), "AiPluginConfig descriptor yielded no keys")
        val doc = DocsSupport
            .read(
                "dsl/lsp-server/src/main/kotlin/io/karandash/lsp/AdvancedValidatorDocs.kt"
            ).substringAfter("\"ai\" to")
            .substringBefore("## Example")
        val documented = Regex("- `(\\w+)").findAll(doc).map { it.groupValues[1] }.toSet()
        listOf("provider", "apiKey", "model", "failOnError", "maxTokenBudget", "strictness", "concurrency").forEach {
            assertTrue(it in documented, "ai hover misses $it")
        }
        assertTrue(documented.all { it in keys }, "ai hover documents keys AiPluginConfig lacks: ${documented - keys}")
    }
}
