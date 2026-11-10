package io.karandash.cli.docs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Stale facts in the AI docs, policy texts and the contributor conventions. */
class DocsHygieneTest {
    private val version = Regex("version = \"([0-9.]+)\"").find(DocsSupport.read("build.gradle.kts")).let {
        checkNotNull(it) { "version" }.groupValues[1]
    }

    @Test
    fun `AI quick start puts reporters at top level`() {
        val yaml = DocsSupport.fencedBlocks(DocsSupport.read("docs/features/AI_FEATURES_REFERENCE.md"), "yaml").first()
        assertTrue(yaml.lines().any { it == "reporters:" }, "reporters: must be a top-level key")
        assertFalse(yaml.lines().any { it == "  reporters:" }, "reporters: nested under ai: is never read")
    }

    @Test
    fun `analyzer counts in the docs match the analyzer modules`() {
        val dir = File(DocsSupport.path("plugins/plugins-ai/analyzers").toString())
        val count = dir.listFiles { f -> File(f, "src").isDirectory }.orEmpty().size
        assertTrue(DocsSupport.read("docs/development/ai-eval.md").contains("the $count AI analyzers"))
    }

    @Test
    fun `security policy lists the current minor and the missing plugin KDoc names the real exception`() {
        val minor = version.substringBeforeLast('.')
        assertTrue(DocsSupport.read("SECURITY.md").contains("| $minor.x"), "SECURITY.md must list $minor.x as supported")
        val kdoc = DocsSupport.read("core/manifest/src/commonMain/kotlin/io/karandash/manifest/MissingPluginBehavior.kt")
        assertFalse(kdoc.contains("io.karandash.cli.errors"))
        assertTrue(kdoc.contains("MissingPluginException"))
    }

    @Test
    fun `contributor conventions name things that exist`() {
        val text = DocsSupport.read("docs/development/conventions.md")
        assertFalse(text.contains("normalizeKeysObj"), "the function is deepNormalizeKeys")
        assertFalse(text.contains("linuxArm64"), "kmp-library declares macosArm64 and macosX64 on macOS")
        assertFalse(text.contains("mockk"), "mockk is not used; mokkery is")
        assertTrue(text.contains("dsl/lsp-server") && text.contains("plugins/bootstrap"), "DAG must list the lsp-server edges")
    }

    @Test
    fun `probe README and conduct texts are current`() {
        val probe = DocsSupport.read("tools/examples/extensibility-probe/README.md")
        assertFalse(probe.contains("P0c.2") || probe.contains("red until"), "the README must not mention internal phases")
        assertTrue(DocsSupport.read("CODE_OF_CONDUCT.md").contains("security/advisories/new"), "CODE_OF_CONDUCT.md needs a contact")
    }

    @Test
    fun `KDocs describe what the code does`() {
        val fileIo = DocsSupport.read("core/file/src/commonMain/kotlin/io/karandash/platform/FileIO.kt")
        assertFalse(
            fileIo.contains("not used internally") || fileIo.contains("@Suppress(\"unused\")"),
            "fileSize is used by LocalContentReader"
        )
        assertFalse(fileIo.contains("validation (e.g., GlobExpander)"), "absolutePathOrSelf has many production callers")
        val error = DocsSupport.read("core/api/src/commonMain/kotlin/io/karandash/api/errors/KarandashError.kt")
        assertFalse(error.contains("replaces the former [ErrorCatalog]"), "ErrorCatalog still exists as a facade")
        val pomsky = DocsSupport.read("core/multipattern/src/commonMain/kotlin/io/karandash/patterns/PomskyCompiler.kt")
        assertFalse(pomsky.contains("Chicory"), "the backend is the pomsky-kt library")
    }
}
