package io.karandash.cli.config

import io.karandash.api.errors.ConfigException
import io.karandash.cli.TestFileHelpers
import io.karandash.cli.commands.validate.ValidateConfigPipeline
import io.karandash.config.pipeline.ConfigFormatRegistry
import io.karandash.logger.NoOpLogger
import io.karandash.plugins.parsers.yaml.YamlFormatModule
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Inheritance through `extends:` on the real local-file config path: what the child ends up with. */
class ExtendsInheritanceKeyBehaviourTest {
    private val formats = ConfigFormatRegistry(listOf(YamlFormatModule()))

    private fun inDir(block: suspend (Path) -> Unit) =
        runTest {
            val dir = TestFileHelpers.createTempDir("extends-inheritance")
            try {
                block(dir)
            } finally {
                TestFileHelpers.deleteTempDir(dir)
            }
        }

    private suspend fun write(
        dir: Path,
        name: String,
        yaml: String,
    ) = TestFileHelpers.writeFile(Path(dir, name), yaml)

    private suspend fun merged(
        dir: Path,
        rootYaml: String,
    ): JsonObject {
        val root = Path(dir, "config.yaml")
        TestFileHelpers.writeFile(root, rootYaml)
        val pipeline = ValidateConfigPipeline(configPathStr = root.toString(), logger = NoOpLogger, formatRegistry = formats)
        return pipeline.mergeChain(YamlFormatModule().parseToJsonElement(rootYaml), root) as JsonObject
    }

    private fun JsonObject.section(name: String): JsonObject = this[name] as JsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.content

    @Test
    fun `extends url brings a base only key into the child and the child value wins on a clash`() =
        inDir { dir ->
            write(dir, "base.yaml", "dry-run: true\nallow-localhost: true\nbaseline: base.json")
            val result = merged(dir, "extends: base.yaml\nbaseline: child.json")
            assertEquals("true", result.text("dryRun"))
            assertEquals("true", result.text("allowLocalhost"))
            assertEquals("child.json", result.text("baseline"))
        }

    @Test
    fun `a three level chain resolves root over middle over grandparent`() =
        inDir { dir ->
            write(dir, "grand.yaml", "baseline: grand.json\ndry-run: true\ndry-run-scope: grand\nallow-localhost: true")
            write(dir, "middle.yaml", "extends: grand.yaml\nbaseline: middle.json\ndry-run-scope: middle")
            val result = merged(dir, "extends: middle.yaml\nbaseline: root.json")
            assertEquals("root.json", result.text("baseline"))
            assertEquals("middle", result.text("dryRunScope"))
            assertEquals("true", result.text("dryRun"))
            assertEquals("true", result.text("allowLocalhost"))
        }

    @Test
    fun `without a merge strategy sections merge deep and the child wins per field`() =
        inDir { dir ->
            write(dir, "base.yaml", "limits:\n  max-findings: 500\n  fetch-timeout: 10s")
            val limits = merged(dir, "extends: base.yaml\nlimits:\n  max-findings: 700").section("limits")
            assertEquals("700", limits.text("maxFindings"))
            assertEquals("10s", limits.text("fetchTimeout"))
        }

    @Test
    fun `extends mergeStrategy deep merges nested sections`() =
        inDir { dir ->
            write(dir, "base.yaml", "limits:\n  max-findings: 500\n  fetch-timeout: 10s")
            val root = "extends:\n  - url: base.yaml\n    merge-strategy: deep\nlimits:\n  max-findings: 700"
            val limits = merged(dir, root).section("limits")
            assertEquals("700", limits.text("maxFindings"))
            assertEquals("10s", limits.text("fetchTimeout"))

            val shallow = merged(dir, root.replace("merge-strategy: deep", "merge-strategy: shallow")).section("limits")
            assertNull(shallow["fetchTimeout"])
        }

    @Test
    fun `extends mergeStrategy shallow replaces a whole section but keeps other base keys`() =
        inDir { dir ->
            write(dir, "base.yaml", "dry-run: true\nlimits:\n  max-findings: 500\n  fetch-timeout: 10s")
            val root = "extends:\n  - url: base.yaml\n    merge-strategy: shallow\nlimits:\n  max-findings: 700"
            val result = merged(dir, root)
            assertEquals("700", result.section("limits").text("maxFindings"))
            assertNull(result.section("limits")["fetchTimeout"])
            assertEquals("true", result.text("dryRun"))
        }

    @Test
    fun `extends mergeStrategy replace drops every base key`() =
        inDir { dir ->
            write(dir, "base.yaml", "dry-run: true\nbaseline: base.json")
            val root = "extends:\n  - url: base.yaml\n    merge-strategy: replace\nbaseline: root.json"
            val result = merged(dir, root)
            assertEquals("root.json", result.text("baseline"))
            assertNull(result["dryRun"])
        }

    @Test
    fun `extends mergeStrategy map modes decide how each array path combines`() =
        inDir { dir ->
            write(dir, "base.yaml", "rules:\n  title-format:\n    prefixes: [a]\n    suffixes: [x]")
            val root = "extends:\n  - url: base.yaml\n    merge-strategy:\n      rules.title-format.prefixes: append\n" +
                "rules:\n  title-format:\n    prefixes: [b]\n    suffixes: [y]"
            val rule = merged(dir, root).section("rules").section("titleFormat")
            assertEquals(listOf("a", "b"), (rule["prefixes"] as JsonArray).map { it.jsonPrimitive.content })
            assertEquals(listOf("y"), (rule["suffixes"] as JsonArray).map { it.jsonPrimitive.content })
        }

    @Test
    fun `extends mergeStrategy map prepend puts the child items first`() =
        inDir { dir ->
            write(dir, "base.yaml", "rules:\n  title-format:\n    prefixes: [a]")
            val root = "extends:\n  - url: base.yaml\n    merge-strategy:\n      rules.title-format.prefixes: prepend\n" +
                "rules:\n  title-format:\n    prefixes: [b]"
            val rule = merged(dir, root).section("rules").section("titleFormat")
            assertEquals(listOf("b", "a"), (rule["prefixes"] as JsonArray).map { it.jsonPrimitive.content })
        }

    @Test
    fun `limits extends maxEntries 2 rejects a list of three parents and 3 accepts it`() =
        inDir { dir ->
            listOf("a", "b", "c").forEach { write(dir, "$it.yaml", "env:\n  $it: \"1\"") }
            val list = "extends: [a.yaml, b.yaml, c.yaml]"

            assertFailsWith<ConfigException> { merged(dir, "limits:\n  extends:\n    max-entries: 2\n$list") }
            assertEquals(setOf("a", "b", "c"), merged(dir, "limits:\n  extends:\n    max-entries: 3\n$list").section("env").keys)
        }

    @Test
    fun `limits glob maxDirectoryDepth 0 hides a parent below the top directory and 5 finds it`() =
        inDir { dir ->
            SystemFileSystem.createDirectories(Path(dir, "sub/deep"))
            write(dir, "sub/deep/x.yaml", "baseline: deep.json")
            val shallow = runCatching { merged(dir, "limits:\n  glob:\n    max-directory-depth: 0\nextends: \"**/x.yaml\"") }

            assertEquals(null, shallow.getOrNull()?.text("baseline"))
            assertEquals("deep.json", merged(dir, "limits:\n  glob:\n    max-directory-depth: 5\nextends: \"**/x.yaml\"").text("baseline"))
        }
}
