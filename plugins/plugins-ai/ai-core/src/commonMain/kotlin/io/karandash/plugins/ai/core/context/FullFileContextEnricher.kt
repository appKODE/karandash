package io.karandash.plugins.ai.core.context

import io.karandash.api.PluginContext
import io.karandash.api.ai.AiContextBlock
import io.karandash.api.ai.AiContextEnricherContext
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.api.ai.BlockBudget
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.plugins.ai.core.FilePriorityScorer
import io.karandash.plugins.ai.core.HunkExpander
import io.karandash.plugins.ai.core.SensitiveFileFilter
import io.karandash.plugins.ai.core.rules.FullFileContextConfig
import io.karandash.plugins.ai.core.rules.FullFileLayout
import io.karandash.plugins.ai.core.rules.FullFileMode
import io.karandash.plugins.ai.core.rules.HunkExpansionConfig
import io.karandash.plugins.ai.core.rules.TruncationPriorityConfig
import io.karandash.security.TextRedactor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * AiContextEnricherModule that sends the complete file content for a small
 * number of high-priority files. When truncation drops every hunk but a file
 * is clearly important, sending the full file gives the AI the surrounding
 * class structure it cannot reconstruct from the diff alone.
 *
 * Relies on [FileChange.newContent], which the CLI fills at the MR head commit
 * through `PlatformServiceModule.fetchFileContent` (GitLab repository files API,
 * local git `git show`), bounded per run; providers that keep the SDK default
 * leave it null and the file is skipped.
 *
 * Safety: [SensitiveFileFilter] blocks `.env`/`.pem`/`.key`/credentials so
 * the provider cannot exfiltrate secrets even if they appear as `newContent`.
 * Emitted content still passes through [TextRedactor.redact].
 *
 * Layout (`layout`): `windowed` (default) sends a file of up to 150 lines whole and a bigger one as numbered windows
 * around its changed hunks (enclosing block via [HunkExpander], `...` for gaps), at most `max-file-lines` lines;
 * `whole` is the previous behaviour, the complete file, files over `max-file-lines` left out.
 *
 * `ai.context.stripImports` (default on) leaves import and package lines out of the windowed layout.
 *
 * Budget: highest priority first, within [BlockBudget.maxChars] (the `knowledge-context.budgets."full-file-context"`
 * entry; unlimited without one). A windowed file that does not fit keeps only the lines nearest its changes (the line
 * cap halves down to 8 or more), a whole one is left out; the block records the cut in [AiContextBlock.cutFromChars].
 *
 * Disabled by default - opt-in via `ai.contextQuality.fullFileContext.enabled`.
 */
class FullFileContextEnricher : AiContextEnricherModule {
    override val id: String = MODULE_ID
    override val name: String = MODULE_NAME
    override val version: String = MODULE_VERSION
    override val description: String = MODULE_DESCRIPTION
    override val dslName: String = DSL_NAME
    override val defaultEnabled: Boolean = false
    override val enricherPriority: Int = ENRICHER_PRIORITY

    private var logger: Logger = NoOpLogger

    override suspend fun initialize(context: PluginContext): Boolean {
        logger = context.logger
        return true
    }

    override suspend fun shutdown() { /* no resources */ }

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
        val parsed = parseConfig(config, logger)
        if (!parsed.isActive()) return null
        val strip = stripImports(config)

        val eligible = changes.filter { change ->
            !SensitiveFileFilter.isSensitive(change.path) &&
                !change.newContent.isNullOrBlank() &&
                (parsed.layout == FullFileLayout.WINDOWED || countLines(change.newContent) <= parsed.maxFileLines)
        }
        if (eligible.isEmpty()) return null

        val scored = FilePriorityScorer.score(eligible, TruncationPriorityConfig(), enrichment = null)
        val selected = scored.take(parsed.maxFiles.coerceAtLeast(1)).map { it.change }
        if (selected.isEmpty()) return null

        // Highest priority first. A file that does not fit the budget is narrowed to the lines nearest its change
        // (halving the line cap) before it is left out: half a file with no gap marks reads as a file that ends early.
        val all = selected.joinToString(SEPARATOR) { render(it, parsed, parsed.maxFileLines, strip) }
        val kept = selected.fold(emptyList<String>()) { acc, change ->
            val fitting = caps(parsed)
                .map { render(change, parsed, it, strip) }
                .firstOrNull { (acc + it).joinToString(SEPARATOR).length <= budget.maxChars }
            if (fitting != null) acc + fitting else acc
        }
        if (kept.isEmpty()) {
            logger.debug("Full file context: no file fits the ${budget.maxChars}-char budget, skipping block")
            return AiContextBlock(BLOCK_HEADING, "", BLOCK_PRIORITY, id, cutFromChars = all.length)
        }
        val body = kept.joinToString(SEPARATOR)
        return AiContextBlock(
            heading = BLOCK_HEADING,
            content = body,
            priority = BLOCK_PRIORITY,
            source = id,
            cutFromChars = all.length.takeIf { body.length < all.length },
        )
    }

    private fun caps(config: FullFileContextConfig): Sequence<Int> =
        if (config.layout == FullFileLayout.WHOLE) {
            sequenceOf(config.maxFileLines)
        } else {
            generateSequence(config.maxFileLines) { it / 2 }.takeWhile { it >= MIN_WINDOW_LINES }
        }

    /**
     * [change] as a fenced section: the whole file when small, else numbered windows of at most [cap] lines; in the
     * windowed layout without import and package lines when [strip].
     */
    private fun render(
        change: FileChange,
        config: FullFileContextConfig,
        cap: Int,
        strip: Boolean,
    ): String {
        val content = change.newContent.orEmpty()
        val lines = content.removeSuffix("\n").split('\n')
        val dropImports = strip && config.layout == FullFileLayout.WINDOWED
        val body = when {
            config.layout == FullFileLayout.WHOLE -> {
                content
            }

            lines.size <= minOf(cap, WHOLE_FILE_LINES) -> {
                if (dropImports) lines.filterNot { IMPORT_LINE.containsMatchIn(it) }.joinToString("\n") else content
            }

            else -> {
                windowed(lines, change.diffContent, cap, dropImports)
            }
        }
        return TextRedactor.redact("### ${change.path}\n```\n$body\n```")
    }

    /**
     * The lines of the enclosing blocks of each hunk, nearest to a changed line first, at most [cap] of them,
     * numbered, with `...` where lines are skipped. A diff that does not match the content gets the file head.
     */
    private fun windowed(
        lines: List<String>,
        diff: String?,
        cap: Int,
        strip: Boolean,
    ): String {
        val windows = diff?.let { HunkExpander.windows(it, lines, HunkExpansionConfig.DEFAULT_MAX_HUNK_LINES) }
            ?: listOf((1..1) to (1..minOf(lines.size, cap)))
        val distance = HashMap<Int, Int>()
        for ((changed, block) in windows) {
            // Top-level code has no enclosing block to detect, so every window keeps at least CONTEXT_MARGIN lines around.
            val from = minOf(block.first, changed.first - CONTEXT_MARGIN).coerceAtLeast(1)
            val to = maxOf(block.last, changed.last + CONTEXT_MARGIN).coerceAtMost(lines.size)
            for (n in from..to) {
                val d = if (n in changed) 0 else minOf(kotlin.math.abs(n - changed.first), kotlin.math.abs(n - changed.last))
                distance[n] = minOf(d, distance[n] ?: d)
            }
        }
        val chosen = distance.entries
            .filterNot { strip && IMPORT_LINE.containsMatchIn(lines[it.key - 1]) }
            .sortedWith(compareBy({ it.value }, { it.key }))
            .take(cap)
            .map { it.key }
            .sorted()
        val width = lines.size.toString().length
        val out = mutableListOf<String>()
        var prev = 0
        for (n in chosen) {
            if (n != prev + 1) out += GAP
            out += "${n.toString().padStart(width)}| ${lines[n - 1]}"
            prev = n
        }
        if (prev < lines.size) out += GAP
        return out.joinToString("\n")
    }

    private fun countLines(content: String?): Int {
        if (content.isNullOrEmpty()) return 0
        var count = 1
        for (ch in content) if (ch == '\n') count++
        return count
    }

    companion object {
        const val MODULE_ID: String = "full-file-context"
        const val MODULE_NAME: String = "Full File Context"
        const val MODULE_VERSION: String = "1.0.0"
        const val MODULE_DESCRIPTION: String = "Sends complete file content for small high-priority files"
        const val DSL_NAME: String = "fullFileContext"
        const val ENRICHER_PRIORITY: Int = 150
        const val BLOCK_PRIORITY: Int = 150
        const val BLOCK_HEADING: String = "#### Full file context"
        private const val SEPARATOR = "\n\n"
        private const val GAP = "..."
        private const val MIN_WINDOW_LINES = 8
        private const val CONTEXT_MARGIN = 10
        private val IMPORT_LINE = Regex("""^\s*(import|package)\b""")
        private const val KEY_CONTEXT = "context"
        private const val KEY_STRIP_IMPORTS = "stripImports"

        /** A file this short is always sent whole in the windowed layout: windows would save nothing. */
        private const val WHOLE_FILE_LINES = 150

        /** Whether `ai.contextQuality.fullFileContext` in the AI plugin [config] sends file content to the model. */
        fun isActive(config: JsonElement): Boolean = parseConfig(config, NoOpLogger).isActive()

        /** `ai.context.stripImports` of the AI plugin [config]; on unless set to false (validated by the plugin config). */
        private fun stripImports(config: JsonElement): Boolean =
            ((config as? JsonObject)?.get(KEY_CONTEXT) as? JsonObject)
                ?.get(KEY_STRIP_IMPORTS)
                ?.let { (it as? JsonPrimitive)?.booleanOrNull } != false

        private fun FullFileContextConfig.isActive(): Boolean = enabled && mode != FullFileMode.DIFF_ONLY

        /** Parse `ai.contextQuality.fullFileContext` directly from the plugin config JsonElement. */
        private fun parseConfig(
            config: JsonElement,
            logger: Logger,
        ): FullFileContextConfig {
            if (config === JsonNull) return FullFileContextConfig()
            val obj = (config as? JsonObject) ?: return FullFileContextConfig()
            val contextQuality = (obj["contextQuality"] as? JsonObject)
                ?: (obj["context-quality"] as? JsonObject)
                ?: return FullFileContextConfig()
            val fullFile = (contextQuality["fullFileContext"] as? JsonObject)
                ?: (contextQuality["full-file-context"] as? JsonObject)
                ?: return FullFileContextConfig()
            return try {
                FullFileContextConfig(
                    enabled = (fullFile["enabled"] as? JsonPrimitive)?.booleanOrNull
                        ?: FullFileContextConfig().enabled,
                    maxFileLines = (fullFile["maxFileLines"] as? JsonPrimitive)?.intOrNull
                        ?: (fullFile["max-file-lines"] as? JsonPrimitive)?.intOrNull
                        ?: FullFileContextConfig.DEFAULT_MAX_FILE_LINES,
                    maxFiles = (fullFile["maxFiles"] as? JsonPrimitive)?.intOrNull
                        ?: (fullFile["max-files"] as? JsonPrimitive)?.intOrNull
                        ?: FullFileContextConfig.DEFAULT_MAX_FILES,
                    mode = (fullFile["mode"] as? JsonPrimitive)?.jsonPrimitive?.content?.let { modeToken ->
                        when (modeToken) {
                            "target" -> FullFileMode.TARGET
                            "both" -> FullFileMode.BOTH
                            "diff-only" -> FullFileMode.DIFF_ONLY
                            else -> FullFileMode.TARGET
                        }
                    } ?: FullFileMode.TARGET,
                    layout = when ((fullFile["layout"] as? JsonPrimitive)?.jsonPrimitive?.content) {
                        "whole" -> FullFileLayout.WHOLE
                        else -> FullFileLayout.WINDOWED
                    },
                )
            } catch (e: IllegalArgumentException) {
                logger.warn("fullFileContext config ignored, using defaults: ${e.message}")
                FullFileContextConfig()
            }
        }
    }
}
