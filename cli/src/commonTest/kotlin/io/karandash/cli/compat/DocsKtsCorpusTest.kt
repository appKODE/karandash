package io.karandash.cli.compat

import io.karandash.api.exception.ConfigParseException
import io.karandash.cli.TestFileHelpers
import io.karandash.parser.KarandashParser
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The docs corpus: every Kotlin DSL config example under `docs/` must parse, and every YAML example
 * that sits next to a KTS example with the same top-level sections must load to the same effective `Config`.
 */
class DocsKtsCorpusTest {
    @Test
    fun `every KTS config example in the docs parses`() {
        val blocks = docBlocks().filter { it.lang == KOTLIN && DocFences.isKtsConfig(it.text) }
        assertTrue(blocks.size >= MIN_KTS_EXAMPLES, "expected at least $MIN_KTS_EXAMPLES KTS examples, got ${blocks.size}")
        val failures = blocks.mapNotNull { block -> parseError(block.text)?.let { "${block.source}: $it" } }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `YAML and KTS twins in the docs load to the same effective config`() =
        runTest(timeout = 5.minutes) {
            val pairs = docBlocks()
                .zipWithNext()
                .filter { (a, b) -> a.source.substringBefore(':') == b.source.substringBefore(':') }
                .mapNotNull { (a, b) -> twin(a, b) }
            assertTrue(pairs.size >= MIN_TWINS, "expected at least $MIN_TWINS YAML/KTS twins, got ${pairs.map { it.second.source }}")
            val mismatches = pairs.mapIndexedNotNull { index, (yaml, kts) ->
                val dir = Path(STAGE_DIR, index.toString())
                SystemFileSystem.createDirectories(dir)
                val yamlPath = Path(dir, "config.yaml").also { TestFileHelpers.writeFile(it, yaml.text) }
                val ktsPath = Path(dir, "config.kts").also { TestFileHelpers.writeFile(it, kts.text) }
                val fromYaml = CompatLoader.load(yamlPath.toString()).effective
                val fromKts = CompatLoader.load(ktsPath.toString()).effective
                when {
                    fromKts.startsWith(ERROR) -> "${kts.source}: $fromKts"
                    fromYaml.startsWith(ERROR) -> "${yaml.source}: $fromYaml"
                    fromYaml != fromKts -> "${yaml.source} vs ${kts.source}:\n--- yaml\n$fromYaml\n--- kts\n$fromKts"
                    else -> null
                }
            }
            assertEquals(emptyList(), mismatches, mismatches.joinToString("\n\n"))
        }

    /** A YAML block next to a KTS block is its twin when both declare the same top-level sections. */
    private fun twin(
        a: DocFences.Block,
        b: DocFences.Block,
    ): Pair<DocFences.Block, DocFences.Block>? {
        val (yaml, kts) = when {
            a.lang == YAML && b.lang == KOTLIN -> a to b
            a.lang == KOTLIN && b.lang == YAML -> b to a
            else -> return null
        }
        if (!DocFences.isKtsConfig(kts.text) || parseError(kts.text) != null) return null
        val ktsKeys = KarandashParser().parseToJson(kts.text).keys
        val yamlKeys = DocFences.yamlTopKeys(yaml.text)
        return if (ktsKeys.isNotEmpty() && ktsKeys == yamlKeys) yaml to kts else null
    }

    private fun parseError(text: String): String? =
        try {
            KarandashParser().parseToJson(text)
            null
        } catch (e: ConfigParseException) {
            e.message ?: e.toString()
        }

    private fun docBlocks(): List<DocFences.Block> = DocFences.blocks()

    private companion object {
        const val STAGE_DIR = "cli/build/docs-corpus"
        const val KOTLIN = "kotlin"
        const val YAML = "yaml"
        const val ERROR = "ERROR"
        const val MIN_KTS_EXAMPLES = 100
        const val MIN_TWINS = 8
    }
}
