package io.karandash.cli.docs

import io.karandash.config.AuthConfig
import io.karandash.config.EnrichmentConfig
import io.karandash.config.ReportConfig
import io.karandash.config.ReportTransparencyConfig
import io.karandash.config.SecurityConfig
import io.karandash.config.pipeline.EXTENDS_CACHE_KEYS
import io.karandash.config.pipeline.EXTENDS_ENTRY_KEYS
import io.karandash.http.HttpAuth
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.LearningsConfig
import io.karandash.plugins.ai.core.config.AiLargeMrConfig
import io.karandash.plugins.ai.core.config.PreMergeCheck
import io.karandash.plugins.ai.core.config.ThreadCommandsConfig
import kotlinx.serialization.descriptors.SerialDescriptor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every `<!-- keys: path -->` table in `docs/reference/configuration-schema.md` lists exactly the keys the parser reads
 * at that path (spelling-insensitive), and none of them is described as deprecated.
 */
class ConfigurationSchemaKeysDocTest {
    private val doc: List<String> =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, DOC) }
            .firstOrNull { it.isFile }
            .let { checkNotNull(it) { "$DOC not found in the working directory or any parent; was the schema doc moved?" } }
            .readLines()

    private val parserKeys: Map<String, Set<String>> =
        mapOf(
            // `ai.enrichment` is read from the raw block by ConfigMapParser, not by the AI plugin
            "ai" to AiPluginConfig.TOP_LEVEL_KEYS,
            "ai.enrichment" to names(EnrichmentConfig.serializer().descriptor),
            "ai.learnings" to names(LearningsConfig.serializer().descriptor),
            "ai.thread-commands" to names(ThreadCommandsConfig.serializer().descriptor),
            "ai.large-mr" to names(AiLargeMrConfig.serializer().descriptor),
            "ai.checks[]" to names(PreMergeCheck.serializer().descriptor),
            "report" to names(ReportConfig.serializer().descriptor),
            "report.transparency" to names(ReportTransparencyConfig.serializer().descriptor),
            "security" to names(SecurityConfig.serializer().descriptor),
            "extends[]" to EXTENDS_ENTRY_KEYS,
            "extends[].cache" to EXTENDS_CACHE_KEYS,
            "extends[].auth" to httpAuthKeys(),
            "auth.<name>" to names(AuthConfig.serializer().descriptor),
        )

    @Test
    fun `each keys table lists exactly the keys the parser reads`() {
        val problems = parserKeys.flatMap { (path, keys) ->
            val documented = table(path)?.keys ?: return@flatMap listOf("$path: no keys table")
            val expected = keys.map(::camel).toSet()
            (expected - documented).map { "$path.$it: read by the parser, missing from the doc" } +
                (documented - expected).map { "$path.$it: in the doc, unknown to the parser" }
        }

        assertEquals(emptyList(), problems, "in $DOC")
    }

    @Test
    fun `no documented key row says Deprecated`() {
        val rows = doc.filter { it.startsWith("| `") && "Deprecated" in it }

        assertEquals(emptyList(), rows, "rows with 'Deprecated' in $DOC: every documented key is live")
    }

    @Test
    fun `noChangesSinceApproval is documented as the same as freshApprovals`() {
        val row = doc.single { it.startsWith("| `noChangesSinceApproval`") }

        assertTrue("freshApprovals" in row && "Deprecated" !in row, row)
    }

    /** Rows of the table after the `<!-- keys: [path] -->` marker, keyed by their camelCase first-cell key. */
    private fun table(path: String): Map<String, String>? {
        val start = doc.indexOf("<!-- keys: $path -->").takeIf { it >= 0 } ?: return null
        return doc
            .drop(start + 1)
            .dropWhile { it.isBlank() }
            .takeWhile { it.startsWith("|") }
            .mapNotNull { row -> FIRST_CELL_KEY.find(row)?.let { camel(it.groupValues[1]) to row } }
            .filterNot { (key, _) -> key.startsWith("<") }
            .toMap()
    }

    private fun names(descriptor: SerialDescriptor): Set<String> =
        (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet()

    /** `type` plus the fields of every `HttpAuth` subtype (sealed descriptor: element 1 holds one descriptor per type). */
    private fun httpAuthKeys(): Set<String> {
        val types = HttpAuth.serializer().descriptor.getElementDescriptor(1)
        return setOf("type") + (0 until types.elementsCount).flatMap { names(types.getElementDescriptor(it)) }
    }

    private fun camel(key: String): String {
        val parts = key.split('-', '_')
        return parts.first() + parts.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    }

    private companion object {
        const val DOC = "docs/reference/configuration-schema.md"
        val FIRST_CELL_KEY = Regex("""^\| `([^`\s]+)`""")
    }
}
