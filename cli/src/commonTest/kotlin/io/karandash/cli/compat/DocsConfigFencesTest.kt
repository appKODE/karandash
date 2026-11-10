package io.karandash.cli.compat

import io.karandash.api.errors.KarandashError
import io.karandash.cli.TestFileHelpers
import io.karandash.config.schema.ConfigSections
import io.karandash.normalize.ConfigKeyNormalizer
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Every karandash config example in the docs (a ```yaml fence with a top-level config section, or a KTS config in a
 * ```kotlin fence) loads through the real CLI config pipeline with no error, no unknown-key warning (E0007)
 * and no invalid `when`.
 * Intentional fragments and negative examples carry [DocFences.SKIP_MARKER].
 */
class DocsConfigFencesTest {
    @Test
    fun `every config example in the docs loads without error unknown key or invalid when`() =
        runTest(timeout = 15.minutes) {
            val blocks = DocFences.blocks().filter { !it.skipped && configFileName(it) != null }
            assertTrue(blocks.size >= MIN_CONFIGS, "expected at least $MIN_CONFIGS config examples, got ${blocks.size}")
            val failures = blocks.mapIndexedNotNull { index, block -> problem(index, block)?.let { "${block.source}: $it" } }
            assertEquals(emptyList(), failures, failures.joinToString("\n"))
        }

    private suspend fun problem(
        index: Int,
        block: DocFences.Block,
    ): String? {
        val dir = Path(STAGE_DIR, index.toString())
        SystemFileSystem.createDirectories(dir)
        val path = Path(dir, checkNotNull(configFileName(block)))
        val text = when {
            block.lang == YAML -> withoutExtends(block.text)
            isRuleFragment(block.text) -> "rules {\n${block.text}\n}"
            else -> block.text
        }
        TestFileHelpers.writeFile(path, text)
        val snapshot = CompatLoader.load(path.toString())
        val unknownKeys = snapshot.warnings.lines().filter { UNKNOWN_KEY_CODE in it || it.contains(INVALID_WHEN) }
        return when {
            snapshot.effective.startsWith(ERROR) -> snapshot.effective
            unknownKeys.isNotEmpty() -> unknownKeys.joinToString("; ")
            else -> null
        }
    }

    private fun configFileName(block: DocFences.Block): String? =
        when {
            block.lang == YAML && isYamlConfig(block.text) -> "config.yaml"
            block.lang == KOTLIN && DocFences.isKtsConfig(block.text) -> "config.kts"
            else -> null
        }

    /** Rule pages show a rule's block on its own (`sizeLimits { ... }`); it loads inside `rules { }`. */
    private fun isRuleFragment(text: String): Boolean {
        val first = text.lines().first { it.isNotBlank() && !it.trim().startsWith("//") }
        return first
            .trim()
            .substringBefore('{')
            .substringBefore('(')
            .trim() !in CONFIG_KEYS + KARANDASH
    }

    /**
     * [text] without its top-level `extends:` section: examples extend remote URLs and files that only exist in the
     * reader's repo, and the test must not reach the network. `ExtendsParserTest` covers the entry forms.
     */
    private fun withoutExtends(text: String): String {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.startsWith("$EXTENDS:") }
        if (start < 0) return text
        val end = (start + 1..lines.size).first { i ->
            i == lines.size || (lines[i].isNotBlank() && !lines[i].first().isWhitespace() && !lines[i].startsWith("-"))
        }
        return (lines.subList(0, start) + lines.subList(end, lines.size)).joinToString("\n")
    }

    /**
     * A YAML example is a karandash config when it is a mapping that declares a config section and nothing a CI/compose
     * file would.
     */
    private fun isYamlConfig(text: String): Boolean {
        val keys = DocFences.yamlTopKeys(text)
        val sequenceRoot = text.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.startsWith("- ") == true
        return !sequenceRoot && keys.any { it in CONFIG_KEYS } && keys.none { it in OTHER_FILE_KEYS }
    }

    private companion object {
        const val STAGE_DIR = "cli/build/docs-config-fences"
        const val KOTLIN = "kotlin"
        const val YAML = "yaml"
        const val ERROR = "ERROR"
        const val EXTENDS = "extends"
        const val KARANDASH = "karandash"

        /** Load warning for a `when` naming an unknown predicate: the policy or rule never applies. */
        const val INVALID_WHEN = "Invalid 'when'"
        const val MIN_CONFIGS = 300
        val UNKNOWN_KEY_CODE = KarandashError.UNKNOWN_YAML_CONFIG_KEY.code
        val CONFIG_KEYS = ConfigSections.all.map(ConfigKeyNormalizer::kebabToCamelCase).toSet()

        /** Top-level keys of GitLab CI, GitHub Actions, Bitbucket/Azure pipelines, AWS buildspecs and docker-compose files. */
        val OTHER_FILE_KEYS = setOf(
            "stages",
            "jobs",
            "on",
            "steps",
            "script",
            "image",
            "services",
            "include",
            "workflow",
            "pipelines",
            "trigger",
            "pool",
            "beforeScript",
            "variables",
            "runs",
            "phases",
            "if",
        )
    }
}
