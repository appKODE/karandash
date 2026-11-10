@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.pipeline

import io.karandash.api.ai.AiService
import io.karandash.api.ai.AiTokenBudget
import io.karandash.api.run.MergeRequestThreads
import io.karandash.api.run.dumpJson
import io.karandash.api.support.rethrowCancellation
import io.karandash.helpers.RuleIds
import io.karandash.models.ChangeType
import io.karandash.models.MergeRequest
import io.karandash.models.ReviewState
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.plugins.ai.core.AiDefaultModels
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.plugins.ai.core.AiStatePersistence
import io.karandash.plugins.ai.core.LanguagePromptOverlays
import io.karandash.plugins.ai.core.TemporalConfig
import io.karandash.plugins.ai.core.TemporalRuleTracker
import io.karandash.plugins.ai.core.diagnostics.ContextQualityDiagnostics
import io.karandash.plugins.ai.core.diagnostics.FindingDropReason
import io.karandash.plugins.ai.core.pipeline.AiFindingEnricher
import io.karandash.plugins.ai.core.pipeline.AiFindingFilter
import io.karandash.plugins.ai.core.pipeline.AiGroundingVerifier
import io.karandash.plugins.ai.core.pipeline.AiMetadataTransformer
import io.karandash.plugins.ai.core.pipeline.ConstitutionConfig
import io.karandash.plugins.ai.core.pipeline.ConstitutionalReviewer
import io.karandash.plugins.ai.core.pipeline.ContradictionDetector
import io.karandash.plugins.ai.core.pipeline.ContradictionsConfig
import io.karandash.plugins.ai.core.pipeline.CorrelationCondition
import io.karandash.plugins.ai.core.pipeline.DedupConfig
import io.karandash.plugins.ai.core.pipeline.FilterConfig
import io.karandash.plugins.ai.core.pipeline.FindingCap
import io.karandash.plugins.ai.core.pipeline.FindingCorrelationEngine
import io.karandash.plugins.ai.core.pipeline.FindingDuplicates
import io.karandash.plugins.ai.core.pipeline.StageRecord
import io.karandash.sdk.Logger
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Applies post-validation processing to AI findings.
 *
 * [AiPipelineExtension.afterValidation][io.karandash.plugins.ai.AiPipelineExtension.afterValidation] runs the stages in
 * this order: constitutional review, grounding, contradiction detection, actionability (hedge) filtering, duplicates,
 * filter (confidence, dedup, caps), verifier (LLM second opinion on the survivors), enrichment, correlation and
 * temporal escalation.
 */
internal class AiFindingPostProcessor(
    private val logger: Logger,
) {
    /**
     * Applies constitutional review: removes (or, with `failureMode: downgrade`, downgrades)
     * findings that violate configured principles. Off when `enabled: false` or no principles are set.
     */
    suspend fun applyConstitutionalReview(
        findings: List<Finding>,
        config: JsonElement?,
        aiService: AiService?,
    ): List<Finding> {
        val aiConfig = resolveAiConfig(config) ?: return findings
        val constitutionConfig = aiConfig.constitution
        if (!constitutionConfig.enabled || constitutionConfig.principles.isEmpty()) return findings

        val service = aiService ?: return findings
        val model = constitutionConfig.model ?: AiDefaultModels.cheapFor(aiConfig.provider, aiConfig.model)
        val downgrade = constitutionConfig.failureMode == ConstitutionConfig.FAILURE_MODE_DOWNGRADE

        val reviewer = ConstitutionalReviewer(
            aiService = service,
            principles = constitutionConfig.principles,
            model = model,
            selfCritiqueRounds = constitutionConfig.selfCritiqueRounds,
            downgradeViolations = downgrade,
        )
        val result = reviewer.review(findings)

        if (result.removedCount > 0) {
            val action = if (downgrade) "downgraded" else "removed"
            logger.info("Constitutional review $action ${result.removedCount}/${result.totalReviewed} AI finding(s)")
        }

        return result.findings
    }

    /**
     * Verifies AI findings are grounded in actual MR evidence; with `--dump-dir` each finding's decision goes to
     * `ai/stages/grounding.json`.
     */
    suspend fun applyGrounding(
        findings: List<Finding>,
        mr: MergeRequest,
        config: JsonElement?,
    ): List<Finding> {
        val aiConfig = resolveAiConfig(config) ?: return findings
        val groundingConfig = aiConfig.grounding
        if (!groundingConfig.enabled) return findings

        val verifier = AiGroundingVerifier(groundingConfig, logger)
        val result = verifier.verify(findings, mr)
        dumpJson("ai/stages/grounding.json") { result.records }

        if (result.refutedCount > 0) {
            logger.info("AI grounding refuted ${result.refutedCount}/${result.totalAiFindings} finding(s)")
        }

        return result.findings
    }

    /**
     * LLM second opinion on WARNING-or-worse AI file findings (`ai.grounding.verifier`, default off);
     * drops the ones judged invalid and fails open. The model defaults to the main `ai.model`. The call counts
     * against the run's [budget] and holds one of its [permits] (`ai.concurrency`).
     */
    suspend fun applyVerifier(
        findings: List<Finding>,
        mr: MergeRequest,
        config: JsonElement?,
        aiService: AiService?,
        budget: AiTokenBudget? = null,
        permits: Semaphore? = null,
    ): List<Finding> {
        val aiConfig = resolveAiConfig(config) ?: return findings
        val verifierConfig = aiConfig.grounding.verifier
        val service = aiService?.takeIf { verifierConfig.enabled } ?: return findings
        val model = verifierConfig.model ?: aiConfig.model ?: AiDefaultModels.cheapFor(aiConfig.provider)
        val result = AiGroundingVerifier(service, model, budget, permits).secondOpinion(findings, mr)
        val dropped = findings.size - result.size
        if (dropped > 0) logger.info("AI verifier dropped $dropped finding(s) judged invalid")
        return result
    }

    /**
     * Detects and removes contradictory findings (`ai.contradictions`): `enabled: false` skips it,
     * `resolution` picks the survivor of each pair. Each removal is logged with its reason.
     */
    fun applyContradictionDetection(
        findings: List<Finding>,
        config: JsonElement? = null,
    ): List<Finding> {
        val contradictions = resolveAiConfig(config)?.contradictions ?: ContradictionsConfig()
        if (!contradictions.enabled) return findings
        val result = ContradictionDetector(contradictions.resolution).detect(findings)
        if (result.contradictionsFound > 0) {
            logger.info("Contradiction detector removed ${result.contradictionsFound} contradictory AI finding(s)")
            result.reasons.forEach { logger.info("  contradiction: $it") }
        }
        return result.findings
    }

    private fun isHedgeExemptCategory(finding: Finding): Boolean =
        FindingCap.isProtected(finding) || finding.metadata[AiFindingMetadataKeys.CATEGORY]?.lowercase() in HEDGE_EXEMPT_CATEGORIES

    /**
     * Drops hedged AI file findings that name no concrete failure at a line (`ai.filter.dropHedged`, default on); findings with confidence >= `ai.filter.hedgeExemptConfidence` (0.7) are kept.
     * So are protected ([FindingCap.isProtected]) and `correctness`/`security` findings: a code suggestion carries no
     * confidence, and recall on those comes first. Each drop is logged as `finding-dropped (HEDGED)`; non-AI and MR-level findings pass through.
     */
    fun applyActionability(
        findings: List<Finding>,
        config: JsonElement?,
    ): List<Finding> {
        val filter = resolveAiConfig(config)?.filter
        if (filter?.dropHedged == false) return findings
        val exempt = filter?.hedgeExemptConfidence ?: FilterConfig.DEFAULT_HEDGE_EXEMPT_CONFIDENCE
        val diagnostics = ContextQualityDiagnostics(logger)
        val hedged = findings.filter {
            val confidence = it.metadata[AiFindingMetadataKeys.CONFIDENCE]?.toDoubleOrNull()
            AiFindingMetadataKeys.AI_MODEL in it.metadata &&
                (confidence == null || confidence < exempt) &&
                !isHedgeExemptCategory(it) &&
                AiActionabilityFilter.isHedgedWithoutEvidence(it)
        }
        if (hedged.isEmpty()) return findings
        hedged.forEach {
            val ref = it.location as? Location.FileRef
            diagnostics.findingDropped(it.rule, ref?.path, ref?.line, FindingDropReason.HEDGED)
        }
        // A dropped comment's companion code suggestion (same reported line) would otherwise render on its own.
        return findings.filter { f ->
            f !in hedged &&
                !(f.rule == AiRuleIds.CODE_SUGGESTIONS && hedged.any { AiMetadataTransformer.pairsWith(it, f) })
        }
    }

    /**
     * Drops AI file findings that repeat another finding of this run on the same line, and (`ai.filter.dedupOpenThreads`,
     * default on) those an open discussion on the MR already covers ([FindingDuplicates]); a dropped comment takes its
     * companion code suggestion with it. Uses the [threads] the platform already lists: fails open when they are
     * missing or cannot be read.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun applyDuplicates(
        findings: List<Finding>,
        config: JsonElement?,
        threads: MergeRequestThreads?,
    ): List<Finding> {
        val aiConfig = resolveAiConfig(config)
        val filter = aiConfig?.filter
        val inRun = if (filter?.deduplicate == false) null else FindingDuplicates.inRun(findings, aiConfig?.dedup ?: DedupConfig())
        inRun?.let { dumpJson("ai/stages/dedup.json") { it.records } }
        val deduped = inRun?.findings ?: findings
        var result = deduped
        if (filter?.dedupOpenThreads != false && threads != null) {
            val listed = try {
                threads.list()
            } catch (e: Exception) {
                // boundary: platform API call; without the threads nothing is judged a duplicate
                e.rethrowCancellation()
                logger.warn("Duplicate check against open discussions skipped: ${e.message}")
                null
            }
            if (listed != null) result = FindingDuplicates.againstThreads(result, listed)
        }
        // merged-away findings by record (a keeper is a copy with merge metadata), thread duplicates by absence
        val merged = inRun
            ?.records
            ?.withIndex()
            ?.filter { it.value.decision == StageRecord.MERGE }
            ?.map { findings[it.index] }
            .orEmpty()
        val gone = merged + deduped.filter { it !in result }
        // A dropped comment's companion code suggestion (same reported line) would otherwise render on its own;
        // one whose comment survives (another rule merged into it) stays.
        val survivors = result.filter { it.rule == AiRuleIds.REVIEW_COMMENTS }
        val kept = result.filter { f ->
            f.rule != AiRuleIds.CODE_SUGGESTIONS ||
                survivors.any { AiMetadataTransformer.pairsWith(it, f) } ||
                gone.none { it.rule != f.rule && AiMetadataTransformer.pairsWith(it, f) }
        }
        if (kept.size < findings.size) logger.info("Duplicate check dropped ${findings.size - kept.size} AI finding(s)")
        return kept
    }

    /**
     * Filters findings by confidence, deduplicates, and caps per configured limits.
     */
    suspend fun applyFilter(
        config: JsonElement?,
        findings: List<Finding>,
        mr: MergeRequest?,
    ): List<Finding> {
        val aiConfig = resolveAiConfig(config)
        val filterConfig = aiConfig?.filter ?: return deduplicateCodeSuggestions(findings)

        val deletedFiles = mr
            ?.changes
            ?.filter { it.changeType == ChangeType.DELETED }
            ?.map { it.path }
            ?.toSet() ?: emptySet()
        val changedFiles = mr
            ?.changes
            ?.map { it.path }
            ?.toSet() ?: emptySet()
        val diffLineRanges = mr
            ?.changes
            ?.associate { change ->
                change.path to extractDiffLineNumbers(change.diffContent)
            }?.filterValues { it.isNotEmpty() } ?: emptyMap()

        val filter = AiFindingFilter(filterConfig, logger, aiConfig.ranking)
        val capped = filter.filterWithRecords(findings, deletedFiles, changedFiles, diffLineRanges)
        dumpJson("ai/stages/ranking.json") { capped.records }
        val filtered = capped.findings

        if (filtered.size < findings.size) {
            val removed = findings.size - filtered.size
            logger.info("AI filter removed $removed finding(s)")

            val removedSet = findings - filtered.toSet()
            removedSet.forEach { f ->
                val confidence = f.metadata[AiFindingMetadataKeys.CONFIDENCE]?.let { "confidence=$it" } ?: "no confidence"
                val loc = (f.location as? Location.FileRef)
                    ?.let { " @ ${it.path}:${it.line}" } ?: ""
                logger.debug(
                    "  filtered [${f.rule}] ${f.severity} $confidence \u2014 " +
                        "${f.message.take(FILTER_LOG_MSG_LIMIT)}$loc",
                )
            }
        }

        return deduplicateCodeSuggestions(filtered)
    }

    /**
     * Enriches findings using LLM-powered message improvement.
     */
    suspend fun applyEnricher(
        config: JsonElement?,
        findings: List<Finding>,
        aiService: AiService?,
    ): List<Finding> {
        val aiConfig = resolveAiConfig(config) ?: return findings
        val enricherConfig = aiConfig.enricher
        if (!enricherConfig.enabled) return findings

        val service = aiService ?: return findings
        val model = aiConfig.model ?: AiDefaultModels.cheapFor(aiConfig.provider)

        val enricher = AiFindingEnricher(service, enricherConfig, model, language = LanguagePromptOverlays.responseLanguageOf(config))
        val enriched = enricher.enrich(findings)

        if (enriched != findings) {
            val updatedCount = enriched.zip(findings).count { (a, b) -> a != b }
            logger.info("AI enricher updated $updatedCount finding(s)")
        }

        return enriched
    }

    /**
     * Correlates findings (`ai.correlation`). Absent, only the default patterns run. Present and
     * enabled, AI findings from different analyzers at the same line are merged first and the
     * configured patterns run next to the defaults; a pattern with `when` runs only when its condition
     * holds for [mr]. `enabled: false` skips correlation.
     */
    fun applyCorrelation(
        findings: List<Finding>,
        config: JsonElement? = null,
        mr: MergeRequest? = null,
    ): List<Finding> {
        val correlation = resolveAiConfig(config)?.correlation
        if (correlation?.enabled == false) return findings
        val configured = CorrelationCondition.filter(correlation?.patterns.orEmpty(), mr, findings, logger)
        // "approval absent" means the approval rule raised no finding, which an approved MR satisfies too.
        val approved = mr?.approvals?.any { it.state == ReviewState.APPROVED } == true
        val defaults = FindingCorrelationEngine.DEFAULT_PATTERNS.filter { !approved || RuleIds.APPROVAL !in it.absentRules }
        val engine = FindingCorrelationEngine(defaults + configured.map { it.toPattern() })
        val merged = if (correlation == null) findings else engine.mergeSameLocation(findings)
        if (merged.size < findings.size) {
            logger.info("Correlation merged ${findings.size - merged.size} same-location AI finding(s)")
        }
        val result = engine.correlate(merged)
        if (result.metaFindingsCount > 0) {
            logger.info("Correlation engine generated ${result.metaFindingsCount} meta-finding(s)")
        }
        return result.findings
    }

    /**
     * Escalates severity for repeat author violations over time. The author history is read from
     * and written back to [persistence]; without a state store there is no history, so findings
     * are returned unchanged.
     */
    fun applyTemporalEscalation(
        findings: List<Finding>,
        mr: MergeRequest,
        persistence: AiStatePersistence?,
        config: TemporalConfig = TemporalConfig(),
    ): List<Finding> {
        if (persistence == null) {
            logger.debug("[ai] temporal escalation: skipped (reason=no-state-store)")
            return findings
        }
        val tracker = TemporalRuleTracker(state = persistence.loadTemporalState(), config = config)
        val result = tracker.applyEscalation(findings, mr.author.username)
        if (result.escalatedCount > 0) {
            logger.info("Temporal escalation: ${result.escalatedCount} finding(s) escalated for repeat author violations")
        }
        if (!config.trackViolations) return result.findings
        tracker.recordViolations(mr.author.username, result.findings)
        persistence.saveTemporalState(tracker.currentState())
        return result.findings
    }

    /**
     * one code suggestion per location (the one with suggested code wins), and none where a review-comments
     * finding already sits, since that comment covers the line.
     */
    private fun deduplicateCodeSuggestions(findings: List<Finding>): List<Finding> {
        val (codeSuggestions, others) = findings.partition { it.rule == AiRuleIds.CODE_SUGGESTIONS }
        if (codeSuggestions.isEmpty()) return findings
        val comments = others.filter { it.rule == AiRuleIds.REVIEW_COMMENTS }
        val commented = comments.mapNotNull { lineOf(it) }.toSet()
        val commentedWithCode = comments.filter { hasCode(it) }.mapNotNull { lineOf(it) }.toSet()
        // A suggestion at a commented line stays only when it brings code the comment there lacks (`mode: separate`).
        val deduped = codeSuggestions
            .filter { f -> lineOf(f)?.let { it !in commented || (hasCode(f) && it !in commentedWithCode) } ?: true }
            .groupBy { lineOf(it) }
            .flatMap { (line, group) ->
                if (line == null) {
                    group
                } else {
                    listOf(group.firstOrNull { hasCode(it) } ?: group.first())
                }
            }
        if (deduped.size < codeSuggestions.size) {
            logger.debug("[ai] code-suggestions: ${codeSuggestions.size - deduped.size} duplicate(s) by location dropped")
        }
        return others + deduped
    }

    private fun hasCode(finding: Finding): Boolean = finding.metadata.containsKey(AiFindingMetadataKeys.SUGGESTED_CODE)

    /** Location key: path and line, or path and message for a file-level finding (no line), so those stay apart. */
    private fun lineOf(finding: Finding): Pair<String, Any>? =
        (finding.location as? Location.FileRef)?.let { it.path to (it.line ?: finding.message) }

    companion object {
        private const val FILTER_LOG_MSG_LIMIT = 100
        private val HEDGE_EXEMPT_CATEGORIES = setOf("correctness", "security")
        private val HUNK_HEADER_REGEX = Regex("""@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

        /**
         * Extracts line numbers from a unified diff hunk header.
         *
         * Parses `@@ -a,b +c,d @@` headers and returns the set of new-file line numbers
         * covered by `+` lines and context lines.
         */
        fun extractDiffLineNumbers(diffContent: String?): Set<Int> {
            if (diffContent == null) return emptySet()
            val lines = diffContent.split('\n')
            val result = mutableSetOf<Int>()
            var currentLine = 0

            for (line in lines) {
                if (line.startsWith("@@")) {
                    val match = HUNK_HEADER_REGEX.find(line)
                    if (match != null) {
                        currentLine = match.groupValues[1].toIntOrNull() ?: 0
                    }
                    continue
                }
                if (currentLine <= 0) continue
                when {
                    // "\ No newline at end of file" annotates the previous line; it is not a file line.
                    line.startsWith("-") || line.startsWith("\\") -> {
                        Unit
                    }

                    // deleted line, don't increment new-file counter
                    line.startsWith("+") -> {
                        result.add(currentLine)
                        currentLine++
                    }

                    else -> {
                        result.add(currentLine)
                        currentLine++
                    }
                }
            }
            return result
        }

        private fun resolveAiConfig(config: JsonElement?): AiPluginConfig? {
            val obj = config as? JsonObject ?: return null
            val aiBlock = obj["ai"] as? JsonObject ?: obj
            return AiPluginConfig.fromPluginConfig(
                AiPluginConfig.topLevelAiBlockToPluginConfigElement(aiBlock),
            )
        }
    }
}
