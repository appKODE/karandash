package io.karandash.cli.config

import io.karandash.api.errors.ConfigException
import io.karandash.cli.TestFileHelpers
import io.karandash.cli.commands.validate.ValidateConfigPipeline
import io.karandash.config.GlobLimitsConfig
import io.karandash.config.pipeline.ConfigFormatRegistry
import io.karandash.config.pipeline.ExtendsEntryResolver
import io.karandash.logger.NoOpLogger
import io.karandash.manifest.ExtendsEntry
import io.karandash.plugins.parsers.yaml.YamlFormatModule
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Glob, conditional and `@manifest` `extends:` entries resolve on the real config path. */
class ExtendsEntryWiringTest {
    private val formats = ConfigFormatRegistry(listOf(YamlFormatModule()))

    private suspend fun merge(
        dir: Path,
        rootYaml: String,
        platformName: String? = null,
    ): JsonObject {
        val root = Path(dir, "config.yaml")
        TestFileHelpers.writeFile(root, rootYaml)
        val pipeline = ValidateConfigPipeline(
            configPathStr = root.toString(),
            logger = NoOpLogger,
            formatRegistry = formats,
            platformName = platformName,
        )
        return pipeline.mergeChain(YamlFormatModule().parseToJsonElement(rootYaml), root) as JsonObject
    }

    private fun withParents(block: suspend (Path) -> Unit) =
        runTest {
            val dir = TestFileHelpers.createTempDir("extends-wiring")
            try {
                SystemFileSystem.createDirectories(Path(dir, "base"))
                TestFileHelpers.writeFile(Path(dir, "base/a.yaml"), "dry-run: true")
                TestFileHelpers.writeFile(Path(dir, "base/b.yaml"), "fail-on-warning: true")
                TestFileHelpers.writeFile(Path(dir, "base/c.yaml"), "allow-localhost: true")
                block(dir)
            } finally {
                TestFileHelpers.deleteTempDir(dir)
            }
        }

    @Test
    fun `glob and brace extends merge every matched parent`() =
        withParents { dir ->
            val merged = merge(dir, "extends: \"base/{a,b}.yaml\"")
            assertEquals("true", merged["dryRun"].toString())
            assertEquals("true", merged["failOnWarning"].toString())
            assertNull(merged["allowLocalhost"])
        }

    @Test
    fun `extends entry pattern key expands a brace pattern and a missing match adds nothing`() =
        withParents { dir ->
            val merged = merge(dir, "extends:\n  - pattern: \"base/{a,b}.yaml\"")
            assertEquals("true", merged["dryRun"].toString())
            assertEquals("true", merged["failOnWarning"].toString())
            assertNull(merged["allowLocalhost"])
            assertNull(merge(dir, "extends:\n  - pattern: \"base/{a}.yaml\"")["failOnWarning"])
        }

    @Test
    fun `glob in a parent config in another directory expands beside that parent`() =
        withParents { dir ->
            SystemFileSystem.createDirectories(Path(dir, "base/shared"))
            TestFileHelpers.writeFile(Path(dir, "base/shared/x.yaml"), "allow-localhost: true")
            TestFileHelpers.writeFile(Path(dir, "base/a.yaml"), "dry-run: true\nextends: \"shared/*.yaml\"")
            val merged = merge(dir, "extends: \"base/a.yaml\"")
            assertEquals("true", merged["dryRun"].toString())
            assertEquals("true", merged["allowLocalhost"].toString())
        }

    @Test
    fun `brace expansion over limits glob maxExpansion fails with E0015`() =
        withParents { dir ->
            val root = "limits:\n  glob:\n    max-expansion: 2\nextends: \"base/{a,b,c}.yaml\""
            val error = assertFailsWith<ConfigException> { merge(dir, root) }
            assertTrue(error.message.orEmpty().contains("E0015"), error.message)
        }

    @Test
    fun `if condition on platform includes or skips the entry`() =
        withParents { dir ->
            val root = "extends:\n  - url: base/a.yaml\n    if: \"platform == 'gitlab'\""
            assertEquals("true", merge(dir, root, platformName = "gitlab")["dryRun"].toString())
            assertNull(merge(dir, root, platformName = null)["dryRun"])
        }

    @Test
    fun `manifest reference resolves from the extends manifest`() =
        withParents { dir ->
            SystemFileSystem.createDirectories(Path(dir, ".karandash"))
            TestFileHelpers.writeFile(
                Path(dir, ".karandash/extends-manifest.yaml"),
                "extends-sets:\n  standard:\n    - \"https://example.com/base.yaml\"",
            )
            val resolver = ExtendsEntryResolver.forConfig(dir, GlobLimitsConfig(), formats, projectDir = dir)
            val sources = resolver.resolveToSources(listOf(ExtendsEntry.ManifestRef(name = "standard")))
            assertEquals(listOf("https://example.com/base.yaml"), sources.map { it.source.url })
        }
}
