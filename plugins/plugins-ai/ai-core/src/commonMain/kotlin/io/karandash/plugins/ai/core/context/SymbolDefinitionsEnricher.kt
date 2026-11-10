package io.karandash.plugins.ai.core.context

import io.karandash.api.PluginContext
import io.karandash.api.ai.AiContextBlock
import io.karandash.api.ai.AiContextEnricherContext
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.api.ai.BlockBudget
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.plugins.ai.core.SensitiveFileFilter
import io.karandash.plugins.ai.core.rules.SymbolDefinitionsConfig
import kotlinx.serialization.json.JsonElement

/**
 * The definitions of identifiers used on changed lines, so the model sees a called function's signature and the
 * start of its body, or the fields of a touched type, without guessing. Definitions are looked up only in the head
 * content of the MR's own changed files ([FileChange.newContent], fetched and bounded by the CLI), with line regexes
 * for Kotlin, Java, Swift, C and C++ declarations. A definition already visible in the diff (on an added or context
 * line) is skipped.
 *
 * Opt-in (`ai.contextQuality.symbolDefinitions.enabled`): enabling it makes the pipeline fetch head content.
 * Priority [BLOCK_PRIORITY] puts the block above full-file context and callers.
 */
class SymbolDefinitionsEnricher : AiContextEnricherModule {
    override val id: String = MODULE_ID
    override val name: String = "Symbol Definitions"
    override val version: String = "1.0.0"
    override val description: String = "Definitions of identifiers used on changed lines, from the changed files"
    override val dslName: String = "symbolDefinitions"
    override val enricherPriority: Int = BLOCK_PRIORITY
    override val defaultEnabled: Boolean = false

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun shutdown() = Unit

    override suspend fun enrich(
        mr: MergeRequest,
        context: AiContextEnricherContext,
    ): Map<String, String> = emptyMap()

    override suspend fun provideBlock(
        mr: MergeRequest,
        changes: List<FileChange>,
        config: JsonElement,
        budget: BlockBudget,
    ): AiContextBlock? {
        if (!isActive(config)) return null
        val definitions = SymbolDefinitions.find(changes.filterNot { SensitiveFileFilter.isSensitive(it.path) })
        if (definitions.isEmpty()) return null
        val body = definitions.joinToString("\n") { "`${it.path}` L${it.line} (`${it.name}`):\n```\n${it.snippet}\n```" }
        return KnowledgeContextFormatter.formatBlock(BLOCK_HEADING, body, BLOCK_PRIORITY, id, budget)
    }

    companion object {
        const val MODULE_ID: String = "symbol-definitions"
        private val CONFIG_KEYS: List<String> = listOf("symbolDefinitions", "symbol-definitions")
        const val BLOCK_PRIORITY: Int = 160
        const val BLOCK_HEADING: String = "#### Definitions of touched symbols"

        /** True when `ai.contextQuality.symbolDefinitions.enabled` is set (and context quality is on). */
        fun isActive(config: JsonElement): Boolean = contextQualityFlag(config, CONFIG_KEYS, SymbolDefinitionsConfig().enabled)
    }
}

/**
 * One definition found by [SymbolDefinitions].
 *
 * Not @Serializable: an in-memory lookup result rendered straight into the prompt, never stored or sent.
 */
internal data class SymbolDefinition(
    val name: String,
    val path: String,
    val line: Int,
    val snippet: String,
)

/** The line-regex declaration index behind [SymbolDefinitionsEnricher]. */
internal object SymbolDefinitions {
    const val MAX_PER_NAME: Int = 2
    const val MAX_PER_FILE: Int = 6
    const val MAX_TOTAL: Int = 20
    const val MAX_SNIPPET_LINES: Int = 12
    private const val MIN_NAME_LENGTH: Int = 3

    private val identifier = Regex("""[A-Za-z_]\w*""")
    private val declarations = listOf(
        Regex("""^\s*(?:[\w@]+\s+)*?(?:class|interface|struct|enum|object|protocol|typealias|union|record|extension)\s+([A-Za-z_]\w*)"""),
        Regex("""^\s*(?:[\w@]+\s+)*?fun\s+(?:<[^>]*>\s*)?(?:[\w.<>?]+\.)?([A-Za-z_]\w*)\s*\("""),
        Regex("""^\s*(?:[\w@]+\s+)*?func\s+([A-Za-z_]\w*)"""),
        Regex("""^\s*(?:[\w@]+\s+)*?(?:val|var|let)\s+([A-Za-z_]\w*)"""),
        Regex("""^\s*#\s*define\s+([A-Za-z_]\w*)"""),
        Regex("""^\s*typedef\s+.*?([A-Za-z_]\w*)\s*;"""),
        Regex("""^\s*using\s+([A-Za-z_]\w*)\s*="""),
        Regex("""^\s*((?:[\w:<>,*&~\[\]]+\s+)+)[*&]?([A-Za-z_~][\w:]*)\s*\("""),
    )
    private val notTypes = setOf("return", "else", "new", "throw", "case", "delete", "if", "while", "for", "switch", "catch", "goto")

    /** Definitions of names used on the changed lines of [changes], searched in their head content, capped. */
    fun find(changes: List<FileChange>): List<SymbolDefinition> {
        val used = usedNames(changes)
        if (used.isEmpty()) return emptyList()
        val index = changes.flatMap { index(it) }.groupBy { it.name }
        val perFile = mutableMapOf<String, Int>()
        return used
            .asSequence()
            .flatMap { name -> index[name].orEmpty().take(MAX_PER_NAME) }
            .filter { definition ->
                val count = perFile[definition.path] ?: 0
                (count < MAX_PER_FILE).also { if (it) perFile[definition.path] = count + 1 }
            }.take(MAX_TOTAL)
            .toList()
    }

    /** Identifiers on the added and removed lines of [changes], in order of first use. */
    fun usedNames(changes: List<FileChange>): List<String> =
        changes
            .flatMap { change -> changedText(change).flatMap { identifier.findAll(it).map { m -> m.value } } }
            .filter { it.length >= MIN_NAME_LENGTH }
            .distinct()

    private fun changedText(change: FileChange): List<String> =
        DiffAnnotations.rows(change.diffContent).filter { it.kind != ' ' }.map { it.text }

    /** Declarations in [change]'s head content that the diff does not already show. */
    private fun index(change: FileChange): List<SymbolDefinition> {
        val lines = change.newContent?.lines() ?: return emptyList()
        val shown = DiffAnnotations
            .rows(change.diffContent)
            .filter { it.kind != '-' }
            .map { it.newLine }
            .toSet()
        return lines.withIndex().mapNotNull { (i, line) ->
            val name = declaredName(line)
            if (name == null || (i + 1) in shown) null else SymbolDefinition(name, change.path, i + 1, snippet(lines, i))
        }
    }

    /** The name [line] declares, or null. */
    internal fun declaredName(line: String): String? {
        val match = declarations.firstNotNullOfOrNull { it.find(line) } ?: return null
        if (match.groupValues.size > 2) {
            val firstWord = match.groupValues[1].trim().substringBefore(' ')
            if (firstWord in notTypes || (line.trimEnd().endsWith(";") && "=" in line)) return null
        }
        return match.groupValues
            .last()
            .substringAfterLast("::")
            .takeIf { it.length >= MIN_NAME_LENGTH && it !in notTypes }
    }

    /** The declaration at [start] up to where its brackets close, at most [MAX_SNIPPET_LINES] lines. */
    private fun snippet(
        lines: List<String>,
        start: Int,
    ): String {
        val out = mutableListOf<String>()
        var depth = 0
        var i = start
        while (i < lines.size && out.size < MAX_SNIPPET_LINES) {
            val line = lines[i]
            out += line
            depth += line.count { it == '{' || it == '(' } - line.count { it == '}' || it == ')' }
            val continues = line.trimEnd().let { it.endsWith("=") || it.endsWith(":") || it.endsWith("->") || it.endsWith(",") } ||
                lines.getOrNull(i + 1)?.trimStart()?.startsWith("{") == true
            if (depth <= 0 && !continues) break
            i++
        }
        val cut = depth > 0 && i < lines.size
        return out.joinToString("\n").trimIndent() + if (cut) "\n…" else ""
    }
}
