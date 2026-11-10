package io.karandash.plugins.ai.core.context

import io.karandash.api.PluginContext
import io.karandash.api.ai.AiContextBlock
import io.karandash.api.ai.AiContextEnricherContext
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.api.ai.BlockBudget
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.security.TextRedactor
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * AiContextEnricherModule that auto-detects project convention files (via
 * [ConventionDetector] + [ConventionExtractors]) and emits them as a single
 * priority-100 [AiContextBlock].
 *
 * Files come from the MR base branch, never the working tree: [basePaths] names them for
 * `PipelineExtension.baseFiles` and [useBaseFiles] receives what the platform returned. A run without
 * base files (no platform, or one that cannot read files) gets no conventions. The detected files are kept
 * whole, highest priority first, up to [MAX_CHARS]; a file that does not fit is left out and the block's
 * [AiContextBlock.cutFromChars] names the cut in the coverage ledger. When every file is cut the block is blank and
 * only carries that cut (the prompt assembler drops blank blocks but still lists their cuts); with nothing detected
 * it is null.
 *
 * Emitted content is routed through [TextRedactor.redact] so any secret-looking
 * strings that appear in convention files (e.g., example API keys in CONTRIBUTING.md)
 * never reach the AI provider.
 *
 * Enabled by default - opt out via `ai.conventionContext.enabled: false` or the nested
 * `ai.contextQuality.conventionContext.enabled: false` (kebab-case variants accepted at
 * both levels). A custom file list can be supplied via `ai.conventionContext.files`.
 */
class ConventionContextEnricher : AiContextEnricherModule {
    override val id: String = MODULE_ID
    override val name: String = MODULE_NAME
    override val version: String = MODULE_VERSION
    override val description: String = MODULE_DESCRIPTION
    override val dslName: String = DSL_NAME
    override val enricherPriority: Int = ENRICHER_PRIORITY
    override val defaultEnabled: Boolean = true

    private val workingDirectory = atomic<String>("")
    private val initialized = atomic<Boolean>(false)
    private val baseFiles = atomic<Map<String, String>>(emptyMap())
    private val warnedEmpty = atomic<Set<String>>(emptySet())
    private var log: Logger = NoOpLogger

    /** Holds [files] read from the MR base for this run; cleared by [shutdown]. */
    fun useBaseFiles(files: Map<String, String>) {
        baseFiles.value = files
        warnedEmpty.value = emptySet()
    }

    /**
     * The repo paths to read from the MR base: the known convention files plus `files`, none when disabled; only
     * `files` when `ai.contextQuality.conventions.autoDetect` is false.
     */
    fun basePaths(config: JsonElement): Set<String> =
        when {
            !isEnabled(config) -> emptySet()
            !autoDetect(config) -> customFilesFrom(config).toSet()
            else -> (ConventionDetector.KNOWN_SOURCES.map { it.path } + customFilesFrom(config)).toSet()
        }

    override suspend fun initialize(context: PluginContext): Boolean {
        workingDirectory.value = context.workingDirectory
        log = context.logger
        initialized.value = true
        return true
    }

    override suspend fun shutdown() {
        baseFiles.value = emptyMap()
        warnedEmpty.value = emptySet()
    }

    /** Key/value enricher - not used by this module; block output goes through [provideBlock]. */
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
        if (!initialized.value || !isEnabled(config)) return null
        val customFiles = customFilesFrom(config)
        val files = if (autoDetect(config)) baseFiles.value else baseFiles.value.filterKeys { it in customFiles }
        if (files.isNotEmpty()) warnEmpty(customFiles.filter { files[it].isNullOrBlank() })
        val detected = ConventionDetector.detect(
            workingDir = workingDirectory.value,
            customFiles = customFiles,
            changedPaths = changes.map { it.path },
            reader = { path -> files[path] },
        )
        if (detected.isEmpty()) return null

        // Whole files only: a file cut mid-way reads as a rule set that ends early.
        val kept = detected.fold(emptyList<ConventionDetector.DetectedConvention>()) { acc, conv ->
            if (acc.sumOf { it.content.length } + conv.content.length <= MAX_CHARS) acc + conv else acc
        }
        val body = render(kept)
        if (body.isEmpty() && kept.size == detected.size) return null

        return AiContextBlock(
            heading = BLOCK_HEADING,
            content = TextRedactor.redact(body),
            priority = BLOCK_PRIORITY,
            source = id,
            cutFromChars = render(detected).length.takeIf { kept.size < detected.size },
        )
    }

    /** A `files` entry the user named but the base gave nothing for warns once per run; built-in files stay silent. */
    private fun warnEmpty(paths: List<String>) {
        val fresh = paths.filter { it !in warnedEmpty.value }
        warnedEmpty.update { it + fresh }
        fresh.forEach { log.warn("ai.convention-context.files: '$it' is empty or missing on the base branch; skipped") }
    }

    private fun render(conventions: List<ConventionDetector.DetectedConvention>): String =
        conventions.joinToString("\n") { conv -> "${labelFor(conv.type, conv.path)}: ${conv.content}" }.trim()

    private fun labelFor(
        type: String,
        path: String
    ): String =
        when (type) {
            ConventionExtractors.TYPE_CODE_STYLE -> "Code style ($path)"
            ConventionExtractors.TYPE_OWNERSHIP -> "Ownership ($path)"
            ConventionExtractors.TYPE_CONTRIBUTING -> "Guidelines ($path)"
            ConventionExtractors.TYPE_LINTER -> "Linter ($path)"
            ConventionExtractors.TYPE_REVIEW_GUIDELINES -> "Review guidelines ($path)"
            ConventionExtractors.TYPE_PR_TEMPLATE -> "PR template ($path)"
            ConventionExtractors.TYPE_CUSTOM -> "Project rules ($path)"
            ConventionExtractors.TYPE_AGENT_RULES -> "Agent instructions ($path)"
            else -> path
        }

    /**
     * Finds the `conventionContext` config block, checking both the flat `ai.conventionContext`
     * shape (original) and the nested `ai.contextQuality.conventionContext` shape (matches the
     * convention other opt-in context-quality enrichers use) -- kebab-case variants accepted at
     * both levels.
     */
    private fun conventionBlock(config: JsonElement): JsonObject? {
        val obj = (config as? JsonObject) ?: return null
        ownBlock(obj)?.let { return it }
        val contextQuality = (obj[CONTEXT_QUALITY_KEY] as? JsonObject)
            ?: (obj[CONTEXT_QUALITY_KEY_KEBAB] as? JsonObject)
            ?: return null
        return ownBlock(contextQuality)
    }

    private fun ownBlock(obj: JsonObject): JsonObject? = (obj[DSL_NAME] as? JsonObject) ?: (obj[DSL_NAME_KEBAB] as? JsonObject)

    /** Reads `enabled` from [conventionBlock]; defaults to [DEFAULT_ENABLED] when absent. */
    private fun isEnabled(config: JsonElement): Boolean =
        (conventionBlock(config)?.get("enabled") as? JsonPrimitive)?.booleanOrNull ?: DEFAULT_ENABLED

    /**
     * `ai.contextQuality.conventions.autoDetect` (kebab `auto-detect` accepted); false keeps the known
     * convention files out and sends only the `files` the user named. Unset detects them, as before.
     */
    private fun autoDetect(config: JsonElement): Boolean {
        val contextQuality = (config as? JsonObject)?.let { it[CONTEXT_QUALITY_KEY] ?: it[CONTEXT_QUALITY_KEY_KEBAB] } as? JsonObject
        val conventions = contextQuality?.get(CONVENTIONS_KEY) as? JsonObject
        val flag = conventions?.let { it[AUTO_DETECT_KEY] ?: it[AUTO_DETECT_KEY_KEBAB] } as? JsonPrimitive
        return flag?.booleanOrNull ?: true
    }

    /** Paths in `conventionContext.files`: plain strings. */
    private fun customFilesFrom(config: JsonElement): List<String> {
        val files = conventionBlock(config)?.get("files") as? JsonArray ?: return emptyList()
        return files.mapNotNull { entry -> (entry as? JsonPrimitive)?.takeIf { it.isString }?.content }
    }

    companion object {
        const val MODULE_ID: String = "convention-context"
        const val MODULE_NAME: String = "Convention Context"
        const val MODULE_VERSION: String = "1.0.0"
        const val MODULE_DESCRIPTION: String = "Auto-detects project coding conventions for AI review context"
        const val DSL_NAME: String = "conventionContext"
        const val ENRICHER_PRIORITY: Int = 100
        const val BLOCK_PRIORITY: Int = 100
        const val BLOCK_HEADING: String = "#### Project conventions"

        // limit: fixed cap, a config key if a repo's rules legitimately run past it

        /** Characters of convention text kept per run, counted over whole files. */
        const val MAX_CHARS: Int = 16_000
        private const val DSL_NAME_KEBAB: String = "convention-context"
        private const val CONTEXT_QUALITY_KEY: String = "contextQuality"
        private const val CONTEXT_QUALITY_KEY_KEBAB: String = "context-quality"
        private const val DEFAULT_ENABLED: Boolean = true
        private const val CONVENTIONS_KEY: String = "conventions"
        private const val AUTO_DETECT_KEY: String = "autoDetect"
        private const val AUTO_DETECT_KEY_KEBAB: String = "auto-detect"
    }
}
