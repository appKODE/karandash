@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.dispatch

import io.karandash.api.ModuleRegistry
import io.karandash.api.ai.AiContextBlock
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.api.ai.BlockBudget
import io.karandash.api.run.RunScopedServices
import io.karandash.api.run.RunServiceKeys
import io.karandash.api.support.rethrowCancellation
import io.karandash.logger.Logger
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.platform.exists
import io.karandash.platform.isFile
import io.karandash.platform.readText
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.CrossFileContextBuilder
import io.karandash.plugins.ai.core.DependencyContextBuilder
import io.karandash.plugins.ai.core.FeedbackStore
import io.karandash.plugins.ai.core.LanguageDetector
import io.karandash.plugins.ai.core.LanguagePromptOverlays
import io.karandash.plugins.ai.core.PriorFindingsPromptEnricher
import io.karandash.plugins.ai.core.PromptPart
import io.karandash.plugins.ai.core.ReviewStrictness
import io.karandash.plugins.ai.core.ScopeMatcher
import io.karandash.plugins.ai.core.context.FullFileContextEnricher
import io.karandash.plugins.ai.core.context.RelatedFileContextEnricher
import io.karandash.plugins.ai.core.incremental.IncrementalContext
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.core.rules.KnowledgeContextConfig
import io.karandash.plugins.ai.core.rules.PerRuleContextResolver
import io.karandash.plugins.ai.prompts.PromptResolver
import io.karandash.plugins.ai.prompts.PromptSource
import io.karandash.plugins.ai.prompts.ResolvedPrompt
import io.karandash.sdk.content.ContentSource
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Overwrites `contextQuality.relatedFiles` in the enricher config with [ruleId]'s resolved typed setting
 * (per-rule override, alias and `contextQuality.enabled` applied), which
 * [RelatedFileContextEnricher] reads. A `context-quality` block is carried over under `contextQuality`.
 */
internal fun withResolvedRelatedFiles(
    config: JsonElement,
    ruleId: String,
    aiRuleConfig: AiRuleConfig,
): JsonElement {
    val resolved = PerRuleContextResolver.resolveForRule(ruleId, aiRuleConfig.contextQuality)
    val enabled = resolved.enabled && resolved.relatedFiles.enabled
    val root = (config as? JsonObject).orEmpty()
    val contextQuality = (root[RelatedFileContextEnricher.CONTEXT_QUALITY_KEY] ?: root["context-quality"]) as? JsonObject
    val sources = JsonObject(mapOf("callers" to JsonPrimitive(resolved.relatedFiles.sources.callers)))
    val relatedFiles = JsonObject(mapOf("enabled" to JsonPrimitive(enabled), "sources" to sources))
    val merged = contextQuality.orEmpty() + (RelatedFileContextEnricher.RELATED_FILES_KEY to relatedFiles)
    return JsonObject(root + (RelatedFileContextEnricher.CONTEXT_QUALITY_KEY to JsonObject(merged)))
}

/**
 * Assembles prompts for AI rule evaluation: resolves the prompt source, collects
 * enricher-contributed context, and builds the extra-instructions block.
 */
internal class AiPromptAssembler(
    private val registry: ModuleRegistry,
    private val logger: Logger,
) {
    /**
     * Resolves the prompt override for [ruleId]. Returns `null` to use the built-in default; the result's
     * [ResolvedPrompt.mrData] is the MR text the prompt references, for the user message.
     */
    suspend fun resolvePromptForRule(
        ruleId: String,
        mr: MergeRequest,
        combinedExtra: String?,
        matchedScopeNames: List<String> = emptyList(),
        promptSources: Map<String, PromptSource>,
        promptResolver: PromptResolver,
        pluginCfgContext: Map<String, String>,
    ): ResolvedPrompt? {
        val source = promptSources[ruleId]
        if (source == null) {
            logger.debug("AI plugin [$ruleId]: no prompt source configured, using builtin")
            return null
        }
        if (source is PromptSource.Builtin) {
            logger.debug("AI plugin [$ruleId]: prompt source is Builtin, using default")
            return null
        }
        // An analyzer without a default prompt still gets the user's source plus the assembled extras.
        val builtinPrompt = registry.findAiAnalyzer(ruleId)?.defaultPrompt.orEmpty()
        val context = if (matchedScopeNames.isNotEmpty()) {
            pluginCfgContext + ("mr.scopes" to matchedScopeNames.joinToString(","))
        } else {
            pluginCfgContext
        }
        val resolved = promptResolver.resolveSplit(ruleId, source, builtinPrompt, mr, combinedExtra, context)
        logger.debug("AI plugin [$ruleId]: prompt resolved (${resolved.systemPrompt.length} chars)")
        return resolved
    }

    /**
     * Builds the extra-instructions block for a rule invocation.
     *
     * @param needsScopeDetection Returns true when the analyzer for [ruleId] requests
     *   language-scope detection as a fallback when no scope instructions match.
     */
    fun buildExtraInstructions(
        ruleId: String,
        mr: MergeRequest,
        pluginCfg: AiPluginConfig,
        aiRuleConfig: AiRuleConfig,
        strictness: ReviewStrictness,
        feedbackStore: FeedbackStore,
        enricherContext: Map<String, String> = emptyMap(),
        priorFindingsByChangedFile: Map<String, List<Finding>> = emptyMap(),
        priorMrLevelFindings: List<Finding> = emptyList(),
        needsScopeDetection: (String) -> Boolean,
    ): String? =
        buildExtraParts(
            ruleId,
            mr,
            pluginCfg,
            aiRuleConfig,
            strictness,
            feedbackStore,
            enricherContext,
            priorFindingsByChangedFile,
            priorMrLevelFindings,
            needsScopeDetection,
        ).render()

    private fun buildExtraParts(
        ruleId: String,
        mr: MergeRequest,
        pluginCfg: AiPluginConfig,
        aiRuleConfig: AiRuleConfig,
        strictness: ReviewStrictness,
        feedbackStore: FeedbackStore,
        enricherContext: Map<String, String>,
        priorFindingsByChangedFile: Map<String, List<Finding>>,
        priorMrLevelFindings: List<Finding>,
        needsScopeDetection: (String) -> Boolean,
        dependencyFiles: Map<String, String>? = null,
    ): AssembledExtras {
        val parts = mutableListOf<Pair<String, String>>()

        // Language and output contract go first, ahead of the context they govern.
        LanguagePromptOverlays.outputLanguageInstruction(aiRuleConfig.responseLanguage)?.let {
            parts.add("responseLanguage" to it)
        }
        val cq = aiRuleConfig.contextQuality
        if (cq.enabled && cq.findingValidator.enabled) {
            parts.add("findingLocationConstraint" to FINDING_LOCATION_CONSTRAINT)
        }

        pluginCfg.extraInstructions?.textOrWarn("extraInstructions", logger)?.let { parts.add("extraInstructions" to it) }
        pluginCfg.bestPractices?.textOrWarn("bestPractices", logger)?.let { parts.add("bestPractices" to "Best practices:\n$it") }

        val matchedScopes = ScopeMatcher.matchScopes(mr.changes, pluginCfg.scopes)
        val scopeBlock = ScopeMatcher.buildScopeInstructions(matchedScopes)
        if (scopeBlock != null) {
            val scopeNames = matchedScopes.joinToString(", ") { it.name }
            logger.debug("AI plugin [$ruleId]: ${matchedScopes.size} scope(s) matched: $scopeNames")
            parts.add("scopes" to scopeBlock)
        } else if (needsScopeDetection(ruleId)) {
            if (matchedScopes.isNotEmpty()) {
                val scopeNames = matchedScopes.joinToString(", ") { it.name }
                logger.debug("AI plugin [$ruleId]: scope(s) matched ($scopeNames) but no instructions configured")
            } else {
                val language = LanguageDetector.detect(mr.changes)
                logger.debug("AI plugin [$ruleId]: no scopes matched, language fallback=$language")
                LanguagePromptOverlays.get(language)?.let { parts.add("languageOverlay" to it) }
            }
        }

        // T0: a matched project instruction is sent whole or the rule is skipped, never cut.
        ScopeMatcher.buildInstructionBlock(ScopeMatcher.matchInstructions(mr.changes, pluginCfg.review.instructions))?.let {
            parts.add(AssembledExtras.REVIEW_INSTRUCTIONS to it)
        }

        val crossFileCfg = pluginCfg.crossFileContext
        val maxContext = pluginCfg.crossFileContextMaxChars
        val cut = mutableMapOf<String, String>()
        // An MR-level analyzer (a summary, a score) cites no lines: cross-file code only crowds its budget.
        val lineAnchored = !wholeMr(ruleId)
        if (lineAnchored && crossFileCfg?.enabled != false) {
            CrossFileContextBuilder.buildContext(mr.changes, Int.MAX_VALUE, pluginCfg.contextImportGraph)?.let {
                parts.add(
                    AssembledExtras.CROSS_FILE_CONTEXT to
                        "Cross-file context:\n${cutTo(AssembledExtras.CROSS_FILE_CONTEXT, it, maxContext, cut)}"
                )
            }
        }
        if (lineAnchored && crossFileCfg?.enabled == true && maxContext > 0) {
            DependencyContextBuilder
                .build(mr.changes, crossFileCfg, Int.MAX_VALUE) {
                    if (dependencyFiles != null) dependencyFiles[it] else readRepoFile(it, logger)
                }?.let {
                    logger.debug("AI plugin [$ruleId]: crossFileContext added ${it.length} chars of dependency context")
                    parts.add(AssembledExtras.DEPENDENCY_CONTEXT to cutTo(AssembledExtras.DEPENDENCY_CONTEXT, it, maxContext, cut))
                }
        }

        strictness.promptOverlay()?.let { parts.add("strictness" to it) }

        if (enricherContext.isNotEmpty()) {
            val enricherBlock = enricherContext.entries.joinToString("\n") { (k, v) -> "$k: $v" }
            parts.add(AssembledExtras.ENRICHER_CONTEXT to "Additional context:\n$enricherBlock")
        }

        val priorEnricher = PriorFindingsPromptEnricher(
            priorByFile = priorFindingsByChangedFile,
            priorMrLevel = priorMrLevelFindings,
            config = pluginCfg.pipeline.priorFindings,
            feedbackStore = feedbackStore,
        )
        if (pluginCfg.feedbackEnabled) priorEnricher.dismissedPrompt(ruleId)?.let { parts.add("feedback" to it) }
        priorEnricher.enrichPrompt(ruleId)?.let { parts.add("priorFindings" to it) }

        logger.info("AI plugin [$ruleId]: extra sections: ${describeSections(parts)}")
        return AssembledExtras(
            parts = parts.map { (name, text) ->
                PromptPart(
                    name,
                    text,
                    pluginCfg.budget.priorityOf(
                        name,
                        AssembledExtras.T2_PRIORITIES[name] ?: 0
                    ),
                    AssembledExtras.tierOf(name),
                )
            },
            t0Names = parts.map { it.first }.filterTo(mutableSetOf()) { it !in AssembledExtras.T2_PRIORITIES },
            cut = cut,
        )
    }

    /**
     * Builds the extra-instructions block and appends structured context blocks produced
     * by enrichers' [io.karandash.api.ai.AiContextEnricherModule.provideBlock].
     *
     * Enrichers run concurrently via [supervisorScope]; individual failures are isolated
     * (logged, not propagated). Blocks are sorted by [AiContextBlock.priority] descending
     * so highest-priority context appears first. Keeps the non-suspend [buildExtraInstructions]
     * call site intact for callers that cannot be `suspend`.
     *
     * @param changes File changes passed to each enricher's `provideBlock`.
     * @param enricherConfigJson `config` forwarded to every enricher's `provideBlock`. Merges
     *   the `ai:` plugin config (read by e.g. `FullFileContextEnricher`, `ConventionContextEnricher`)
     *   with live enrichment provider data keyed by enrichment ID (read by e.g.
     *   `TicketContentEnricher`, `CiContextEnricher`, `SymbolContextEnricher`, `DesignDocEnricher`) --
     *   see `AiRuleModule.mergedEnricherConfigJson`. The two key sets never collide: enrichment
     *   IDs always contain a `:`, `ai:` config keys never do.
     */
    suspend fun buildExtraInstructionsWithContext(
        ruleId: String,
        mr: MergeRequest,
        changes: List<FileChange>,
        pluginCfg: AiPluginConfig,
        aiRuleConfig: AiRuleConfig,
        strictness: ReviewStrictness,
        feedbackStore: FeedbackStore,
        enricherContext: Map<String, String> = emptyMap(),
        incrementalContext: IncrementalContext? = null,
        enricherConfigJson: JsonElement = JsonNull,
        needsScopeDetection: (String) -> Boolean,
    ): String? =
        assembleExtras(
            ruleId,
            mr,
            changes,
            pluginCfg,
            aiRuleConfig,
            strictness,
            feedbackStore,
            enricherContext,
            incrementalContext,
            enricherConfigJson,
            needsScopeDetection,
        ).render()

    /**
     * The parts behind [buildExtraInstructionsWithContext], each tagged T0 or T2 so the caller can fit them to
     * `ai.context.budget`. Enricher blocks are named by enricher id; a T2 block gets the
     * `contextQuality.knowledgeContext` budget of its enrichment ids, a T0 block (tickets) none.
     */
    suspend fun assembleExtras(
        ruleId: String,
        mr: MergeRequest,
        changes: List<FileChange>,
        pluginCfg: AiPluginConfig,
        aiRuleConfig: AiRuleConfig,
        strictness: ReviewStrictness,
        feedbackStore: FeedbackStore,
        enricherContext: Map<String, String> = emptyMap(),
        incrementalContext: IncrementalContext? = null,
        enricherConfigJson: JsonElement = JsonNull,
        needsScopeDetection: (String) -> Boolean,
    ): AssembledExtras {
        // A config change drops prior findings on save, so "do not repeat" them would lose them for good
        val priors = incrementalContext?.takeUnless { it.configChanged }
        val base = buildExtraParts(
            ruleId = ruleId,
            mr = mr,
            pluginCfg = pluginCfg,
            aiRuleConfig = aiRuleConfig,
            strictness = strictness,
            feedbackStore = feedbackStore,
            enricherContext = enricherContext,
            priorFindingsByChangedFile = priors?.priorFindingsByChangedFile.orEmpty(),
            priorMrLevelFindings = priors?.priorMrLevelFindings.orEmpty(),
            needsScopeDetection = needsScopeDetection,
            dependencyFiles = if (wholeMr(ruleId)) null else fetchDependencyFiles(mr, pluginCfg),
        )

        val knowledge = PerRuleContextResolver.resolveForRule(ruleId, aiRuleConfig.contextQuality).knowledgeContext
        val config = withResolvedRelatedFiles(enricherConfigJson, ruleId, aiRuleConfig)
        val blocks = collectProviderBlocks(ruleId, mr, changes, config, knowledge)
        if (blocks.isEmpty()) return base
        logger.info("AI plugin [$ruleId]: context blocks: ${describeSections(blocks.map { (_, b) -> b.source to b.content })}")

        // A blank block cut from content (nothing fit its budget) is not sent, only named among the cuts.
        val blockParts = blocks
            .filter { (_, block) -> block.content.isNotBlank() }
            .sortedByDescending { (_, block) -> block.priority }
            .map { (enricherId, block) ->
                val text = wrapUntrusted(block)
                PromptPart(
                    enricherId,
                    text,
                    pluginCfg.budget.priorityOf(enricherId, block.priority),
                    AssembledExtras.tierOf(enricherId),
                    keepTail = UNTRUSTED_CLOSE.length,
                )
            }
        val t0Blocks = blocks.map { it.first }.filter { it in AssembledExtras.T0_ENRICHERS }
        val blockCuts = blocks.mapNotNull { (id, block, maxChars) ->
            // An unbudgeted (T0) block names what it kept rather than its unlimited budget.
            val kept = if (maxChars == Int.MAX_VALUE) block.content.length else maxChars
            block.cutFromChars?.let { from -> id to "$id (cut to $kept of $from chars)" }
        }
        return AssembledExtras(base.parts + blockParts, base.t0Names + t0Blocks, base.cut + blockCuts)
    }

    /**
     * `ai.crossFileContext` files read before prompt assembly at the MR head through [RunServiceKeys.REPOSITORY_FILE]
     * (the working copy when it is at the head, else the platform); `null` without a reader, so the working copy is read.
     */
    private suspend fun fetchDependencyFiles(
        mr: MergeRequest,
        pluginCfg: AiPluginConfig,
    ): Map<String, String>? {
        val config = pluginCfg.crossFileContext?.takeIf { it.enabled && pluginCfg.crossFileContextMaxChars > 0 } ?: return emptyMap()
        val reader = RunScopedServices.current()?.get(RunServiceKeys.REPOSITORY_FILE) ?: return null
        val ref = mr.diffRefs?.headSha ?: mr.sourceBranch
        return DependencyContextBuilder.fetchRelated(mr.changes, config) { path -> reader.read(ref, path) }
    }

    /** Whether [ruleId] is an MR-level analyzer, whose findings anchor to no line. */
    private fun wholeMr(ruleId: String): Boolean = registry.findAiAnalyzer(ruleId)?.mrLevel == true

    /**
     * Runs all enrichers' [io.karandash.api.ai.AiContextEnricherModule.provideBlock] concurrently, each with
     * its [blockBudget]; a zero budget skips the enricher. An enricher that declares enrichment ids runs only
     * when [config] carries at least one of them, that is when an enabled provider delivered data this rule is
     * scoped to; one without ids (full-file, related files, conventions) gates on its own flags.
     * Returns (enricher id, block, block budget) triples.
     */
    private suspend fun collectProviderBlocks(
        ruleId: String,
        mr: MergeRequest,
        changes: List<FileChange>,
        config: JsonElement,
        knowledge: KnowledgeContextConfig,
    ): List<Triple<String, AiContextBlock, Int>> {
        val delivered = (config as? JsonObject)?.keys.orEmpty()
        val wholeMr = wholeMr(ruleId)
        val enrichers = registry.aiContextEnrichers().filter { enricher ->
            (!wholeMr || enricher.id != FullFileContextEnricher.MODULE_ID) &&
                enricher.handledEnrichmentIds().let { ids -> ids.isEmpty() || ids.any { it in delivered } }
        }
        if (enrichers.isEmpty()) return emptyList()
        return supervisorScope {
            enrichers
                .map { enricher -> enricher to blockBudget(enricher, knowledge) }
                .filter { (_, budget) -> budget.maxChars > 0 }
                .map { (enricher, budget) ->
                    async {
                        try {
                            enricher.provideBlock(mr, changes, config, budget)?.let { Triple(enricher.id, it, budget.maxChars) }
                        } catch (
                            @Suppress("TooGenericExceptionCaught") e: Exception,
                        ) {
                            // boundary: third-party enricher; one failing block must not drop the prompt
                            e.rethrowCancellation()
                            logger.warn("AI plugin [$ruleId]: enricher ${enricher.id} provideBlock failed: ${e.message}")
                            null
                        }
                    }
                }.mapNotNull { it.await() }
        }
    }
}

/** Closes an enricher block; kept when the context budget cuts the block. */
private const val UNTRUSTED_CLOSE = "\n</untrusted-content>"

/** An enricher [block] as the prompt carries it: its heading, then its content marked as untrusted. */
internal fun wrapUntrusted(block: AiContextBlock): String =
    "${block.heading}\n<untrusted-content source=\"${block.source}\">\n${block.content}$UNTRUSTED_CLOSE"

/**
 * T0 enrichers get no budget. A T2 enricher gets the largest `knowledgeContext` budget among its enrichment
 * ids; one without ids (full-file, related files) is capped only by a `budgets` entry under its own id.
 */
private fun blockBudget(
    enricher: AiContextEnricherModule,
    knowledge: KnowledgeContextConfig,
): BlockBudget {
    if (enricher.id in AssembledExtras.T0_ENRICHERS) return BlockBudget.UNLIMITED
    val ids = enricher.handledEnrichmentIds()
    val chars = if (ids.isEmpty()) knowledge.budgets[enricher.id]?.maxChars else ids.maxOf { knowledge.budgetFor(it).maxChars }
    return chars?.let(::BlockBudget) ?: BlockBudget.UNLIMITED
}

/** [text] cut to [maxChars], recording the cut under [name] in [cut] for the coverage ledger. */
private fun cutTo(
    name: String,
    text: String,
    maxChars: Int,
    cut: MutableMap<String, String>,
): String {
    if (text.length <= maxChars) return text
    cut[name] = "$name (cut to $maxChars of ${text.length} chars)"
    return text.take(maxChars)
}

/** Renders `name=<chars>` pairs for the per-rule prompt-assembly info log. */
private fun describeSections(sections: List<Pair<String, String>>): String =
    if (sections.isEmpty()) "none" else sections.joinToString(", ") { (name, text) -> "$name=${text.length} chars" }

/** Reads a repository-relative file for `ai.crossFileContext`; `null` when missing or unreadable. */
private fun readRepoFile(
    path: String,
    logger: Logger,
): String? =
    try {
        Path(path).takeIf { it.exists() && it.isFile() }?.readText()
    } catch (e: IOException) {
        logger.warn("AI plugin: crossFileContext could not read '$path' (${e.message}); skipping")
        null
    }

/**
 * Returns the text of [ContentSource.Inline] or [ContentSource.LocalFile]; warns and returns null when the
 * file cannot be read or the source type is not resolvable at prompt-assembly time.
 */
private fun ContentSource.textOrWarn(
    label: String,
    logger: Logger,
): String? =
    when (this) {
        is ContentSource.Inline -> {
            value
        }

        is ContentSource.LocalFile -> {
            try {
                val file = Path(path)
                if (file.exists() && file.isFile()) {
                    file.readText()
                } else {
                    logger.warn("AI plugin: $label file '$path' not found; ignoring")
                    null
                }
            } catch (e: IOException) {
                logger.warn("AI plugin: $label file '$path' unreadable (${e.message}); ignoring")
                null
            }
        }

        else -> {
            logger.warn("AI plugin: $label source ${this::class.simpleName} is not resolved here; ignoring")
            null
        }
    }

/**
 * Prompt constraint appended to extra-instructions when the finding-location
 * validator is enabled. Tells the AI that context sections (related files,
 * conventions, full-file snippets) are read-only - findings must target the
 * actual diff to avoid post-dispatch drops by [FindingLocationValidator].
 */
private const val FINDING_LOCATION_CONSTRAINT = """FINDING LOCATION RULES:
- Only produce findings for lines that appear in the DIFF section below.
- Use context sections (related files, conventions, full-file snippets, enrichment) to UNDERSTAND the code, but NEVER create findings that reference those files or lines.
- Every finding path MUST be a file present in the diff.
- Every finding line number MUST be within a changed hunk (lines with a `+` prefix)."""
