package io.karandash.cli.compat

import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.TestFileHelpers
import io.karandash.config.RuleExecutionMeta
import io.karandash.config.pipeline.UnknownTopLevelKeys
import io.karandash.normalize.ConfigKeyNormalizer.kebabToCamelCase
import io.karandash.sdk.config.unknownConfigKeys
import io.karandash.sdk.typed.TypedModule
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `docs/reference/configuration-schema.md` names only keys the parser reads: its top-level table lists exactly the
 * built-in top-level keys, and every option in a rule's table is a field of that rule's typed config.
 */
class ConfigurationSchemaDocTest {
    private val doc: List<String> = TestFileHelpers.readFile(Path(DOC)).lines()

    @Test
    fun `the top-level table lists exactly the built-in top-level keys`() {
        val documented = firstColumnKeys(section("## Top-Level Structure", "## ")).toSet()

        assertEquals(UnknownTopLevelKeys.known.sorted(), documented.sorted())
    }

    @Test
    fun `every rule option in the doc is a field of that rule config`() =
        runTest {
            val bootstrap = PluginBootstrap(envReader = { null }).also { it.loadPluginsFromDirs(emptyList()) }
            val typedByRule = bootstrap
                .registry()
                .ruleModules()
                .flatMap { module ->
                    val typed = module as? TypedModule<*> ?: return@flatMap emptyList()
                    module.contributedRules().map { kebabToCamelCase(it) to typed }
                }.toMap()
            val rules = ruleSections()
            val problems = rules.flatMap { (rule, keys) ->
                val typed = typedByRule[rule] ?: return@flatMap listOf("$rule: no typed rule module contributes it")
                val options = JsonObject((keys - RuleExecutionMeta.META_FIELD_NAMES).associateWith { JsonNull })
                unknownConfigKeys(typed.configSerializer.descriptor, options).map { "$rule.$it" }
            }

            assertEquals(emptyList(), problems, "in $DOC (${rules.size} rule sections)")
        }

    /** Lines after the [heading] line up to the next line starting with [end]. */
    private fun section(
        heading: String,
        end: String,
    ): List<String> = doc.dropWhile { it != heading }.drop(1).takeWhile { !it.startsWith(end) }

    /** `### \`ruleId\`` sections under `## Rules` and the option keys of the first table in each. */
    private fun ruleSections(): Map<String, List<String>> {
        val rules = section("## Rules", "## ")
        return rules
            .withIndex()
            .mapNotNull { (index, line) -> RULE_HEADING.matchEntire(line)?.let { it.groupValues[1] to index } }
            .associate { (rule, index) ->
                val body = rules.drop(index + 1).takeWhile { !it.startsWith("### ") }
                rule to firstColumnKeys(body.dropWhile { !it.startsWith("|") }.takeWhile { it.startsWith("|") })
            }
    }

    /** The first backticked word of each table row's first column, header and separator rows skipped. */
    private fun firstColumnKeys(lines: List<String>): List<String> =
        lines.mapNotNull { line -> FIRST_CELL_KEY.find(line)?.groupValues?.get(1) }

    private companion object {
        const val DOC = "docs/reference/configuration-schema.md"
        val RULE_HEADING = Regex("""### `(\w+)`""")
        val FIRST_CELL_KEY = Regex("""^\| `([^`\s]+)`""")
    }
}
