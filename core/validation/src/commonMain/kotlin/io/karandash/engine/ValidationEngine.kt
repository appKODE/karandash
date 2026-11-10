@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.engine

import io.karandash.api.ModuleRegistry
import io.karandash.api.cache.KarandashCache
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.context.ValidationContext
import io.karandash.api.finding.ReviewProvenance
import io.karandash.api.finding.SkipCategory
import io.karandash.api.finding.SkippedRuleInfo
import io.karandash.api.module.FileGranularRuleModule
import io.karandash.api.module.FindingsAwareRuleModule
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.SkipReasonProvider
import io.karandash.api.run.RunContext
import io.karandash.cache.CacheManager
import io.karandash.config.Config
import io.karandash.config.KarandashLimits
import io.karandash.config.RuleExecutionMeta
import io.karandash.config.RuleMode
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.models.ActivePolicy
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.finding.deduplicated
import io.karandash.state.RuleExecutionResult
import io.karandash.utils.GlobMatcher
import io.karandash.utils.finding.AnchorFingerprint
import io.karandash.utils.finding.FindingFingerprint
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlin.time.measureTimedValue

/**
 * Core validation engine that orchestrates rule execution via the plugin registry.
 *
 * Rules execute in two passes:
 *
 * **Pass 1 (NORMAL):** Rules with [RuleMode.NORMAL] are grouped by
 * [RuleExecutionMeta.priority] and run sequentially group-by-group (ascending priority).
 * Within each group rules run in parallel. If any rule in a group has
 * [RuleExecutionMeta.bailOnFail] set and produces an [Severity.ERROR] finding,
 * all subsequent groups are skipped.
 *
 * **Pass 2 (SUPPLEMENT/FALLBACK):** Rules with [RuleMode.SUPPLEMENT] or [RuleMode.FALLBACK]
 * run after pass 1 completes. They receive a [ValidationContext] containing all pass-1
 * findings. Modules implementing [FindingsAwareRuleModule] receive the context via
 * [FindingsAwareRuleModule.validateWithFindings]; other modules fall back to
 * [io.karandash.api.module.RuleModule.validate].
 *
 * Rules with a non-null [RuleExecutionMeta.`when`] are filtered before execution:
 * the condition expression is evaluated against the [MergeRequest] (and pass-1 findings
 * for pass-2 rules), and the rule is skipped if the condition is `false`.
 *
 * Only rules present in [io.karandash.config.RulesConfig.ruleConfigs] are run -- unconfigured
 * rules registered in the module registry are not executed.
 *
 * @param config Resolved validation configuration.
 * @param registry Module registry for rule handler lookup.
 * @param configExtractor Extracts per-rule config elements from the resolved config.
 * @param enabledModules Resolved enablement map from [io.karandash.config.pipeline.ModuleEnablementResolver].
 *        Maps module-id to enabled state. Rules not in this map fall back to [Module.defaultEnabled].
 * @param policy Top-level `policies:` block, its engine and the CI facts policy conditions read.
 * @param enrichmentContext Data collected from enrichment providers this run. Rules declaring
 *        [io.karandash.api.module.RuleModule.requiredEnrichment] are skipped (with a
 *        [SkipCategory.MISSING_REQUIRED_ENRICHMENT] entry) when any required ID is unavailable.
 * @param run Current run; when set, rules receive it via the four-argument `validate`.
 * @param platform Platform the MR came from; rules needing a field it never fills
 *   ([PlatformServiceModule.unavailableMergeRequestFields]) are skipped with a warning.
 * @param ruleCache Rule-result cache: a rule whose MR, config and pass-1 context are unchanged since
 *        its last run is served from here (`fromCache = true`). Defaults to an engine-local in-memory cache;
 *        null disables rule caching (`--no-cache`).
 * @param invalidatedRules Rule ids (`--invalidate-rules`) recomputed this run; their fresh results replace the cached ones.
 *
 * @lifecycle per-run -- do not reuse across invocations
 */
class ValidationEngine(
    private val config: Config,
    private val registry: ModuleRegistry,
    private val configExtractor: ((Config, String) -> List<JsonElement>)? = null,
    private val enabledModules: Map<String, Boolean> = emptyMap(),
    policy: PolicyOptions = PolicyOptions(),
    private val logger: Logger = NoOpLogger,
    private val enrichmentContext: EnrichmentContext = EnrichmentContext.EMPTY,
    private val run: RunContext? = null,
    ruleCache: KarandashCache? = CacheManager.disabled().getStringCache(RULE_CACHE_NAME, RULE_CACHE_MAX_ENTRIES),
    private val platform: PlatformServiceModule? = null,
    invalidatedRules: Set<String> = emptySet(),
) {
    private val ruleResults =
        RuleResultCache(
            ruleCache,
            logger,
            enrichmentContext,
            config.ruleEnrichment.orEmpty().mapValues { it.value.enrich },
            invalidatedRules.mapTo(mutableSetOf(), registry::canonicalRuleId),
        )

    private val policyApplicator = PolicyApplicator(
        config = config,
        registry = registry,
        policyEngine = policy.engine,
        policies = policy.policies,
        logger = logger,
        envLookup = policy.envLookup,
        ciPlatform = policy.ciPlatform,
        isCI = policy.isCI,
        patternExpander = policy.patternExpander,
    )
    private val collectedResults = atomic(emptyList<RuleExecutionResult>())
    private val engineTimeSource = TimeSource.Monotonic
    private var engineStartMark = engineTimeSource.markNow()

    companion object {
        /** Default findings cap before truncation; the engine reads `limits.maxFindings` (clamped). */
        const val MAX_FINDINGS = KarandashLimits.MAX_FINDINGS

        /** Rule ID used for engine-level findings (e.g. truncation warnings). */
        const val ENGINE_RULE_ID = "karandash:engine"

        /** Name of the rule-result cache in [io.karandash.api.cache.CacheProvider.getStringCache]. */
        const val RULE_CACHE_NAME = "rules"

        /** Default entry bound for the rule-result cache. */
        const val RULE_CACHE_MAX_ENTRIES = 1_000

        private const val NS_PER_US = 1000L
        private const val US_PER_MS = 1000L
    }

    suspend fun validate(mr: MergeRequest): ValidationResult {
        engineStartMark = engineTimeSource.markNow()
        collectedResults.value = emptyList()
        val policyResult = policyApplicator.applyPolicies(mr)
        val runtimeConfig = policyResult.runtimeConfig
        val appliedPolicies = policyResult.activePolicies
        val policyEnabledOverrides = policyResult.enabledOverrides
        if (policyEnabledOverrides.isNotEmpty()) {
            logger.debug("Policy enablement overrides: $policyEnabledOverrides")
        }

        val allFindings = mutableListOf<Finding>()
        val skippedRules = mutableListOf<SkippedRuleInfo>()
        skippedRules.addAll(policyResult.skippedPolicyOverrides)

        val effectiveEnabled = enabledModules + policyEnabledOverrides

        val allConfiguredRuleIds = runtimeConfig.rules.ruleConfigs.keys.filter { ruleId ->
            isRuleEnabled(ruleId, effectiveEnabled)
        }
        logger.debug("Enabled rules: ${allConfiguredRuleIds.size} of ${runtimeConfig.rules.ruleConfigs.keys.size} configured")
        logger.debug("Enabled rule IDs: $allConfiguredRuleIds")

        skippedRules.addAll(policyDisabledSkips(runtimeConfig, policyEnabledOverrides, allConfiguredRuleIds, appliedPolicies))
        val whenByRule = allConfiguredRuleIds.mapNotNull { id ->
            runtimeConfig.rules
                .metaFor(id)
                .`when`
                ?.let { id to it.expression }
        }
        warnPredicatesOnMissingFields(whenByRule.toMap(), policyApplicator.buildPredicateRegistry(null).all(), platform, logger)

        val (pass1RuleIds, pass2RuleIds) = allConfiguredRuleIds.partition { ruleId ->
            runtimeConfig.rules.metaFor(ruleId).mode == RuleMode.NORMAL
        }

        val pass1WhenResult = policyApplicator.filterByWhen(runtimeConfig, pass1RuleIds, mr, priorFindings = null)
        skippedRules.addAll(whenConditionSkips(pass1RuleIds, pass1WhenResult))
        allFindings.addAll(whenErrorFindings(pass1WhenResult))
        val pass1RuntimeConfig = pass1WhenResult.runtimeConfig
        val pass1Skipped = pass1RuleIds.size - pass1WhenResult.activeRuleIds.size
        if (pass1Skipped > 0) logger.debug("Pass 1: $pass1Skipped rule(s) skipped by 'when' condition")

        val (pass1ActiveRuleIds, pass1EnrichmentSkips) = filterByRuleRequirements(pass1RuntimeConfig, pass1WhenResult.activeRuleIds)
        skippedRules.addAll(pass1EnrichmentSkips)

        val pass1Findings = executeRuleGroups(
            pass1RuntimeConfig,
            pass1ActiveRuleIds,
            mr,
            context = null,
            skippedRules,
        )
        allFindings.addAll(pass1Findings)

        if (pass2RuleIds.isNotEmpty()) {
            allFindings.addAll(executePass2(runtimeConfig, pass2RuleIds, mr, pass1Findings, skippedRules))
        }

        // Providers over every configured rule, so a module whose rules a policy switched off still hears of it
        skipReasonProviders(
            runtimeConfig.rules.ruleConfigs.keys
                .toList()
        ).forEach { it.onEngineSkips(skippedRules.toList()) }
        val skipReasonProviders = skipReasonProviders(allConfiguredRuleIds)
        skippedRules.addAll(skipReasonProviders.flatMap { it.skippedRuleReasons().values })

        return ValidationResult(
            findings = finalizeFindings(runtimeConfig, allFindings),
            skippedRules = skippedRules,
            appliedPolicies = appliedPolicies,
            ruleExecutionResults = collectedResults.value,
            // first wins, not merged: only the AI extension reports coverage, and ReviewProvenance has no sound merge
            // (file sets of two providers may overlap). Kotlin 2.4 emits real JVM default methods, so no AbstractMethodError guard.
            provenance = skipReasonProviders.firstNotNullOfOrNull { it.reviewProvenance() },
        )
    }

    /** Skip entries for configured rules a matched policy switched off. */
    private fun policyDisabledSkips(
        runtimeConfig: Config,
        policyEnabledOverrides: Map<String, Boolean>,
        allConfiguredRuleIds: List<String>,
        appliedPolicies: List<ActivePolicy>,
    ): List<SkippedRuleInfo> {
        val policyDisabledRules = runtimeConfig.rules.ruleConfigs.keys.filter { ruleId ->
            policyEnabledOverrides[ruleId] == false && ruleId !in allConfiguredRuleIds
        }
        return policyDisabledRules.map { ruleId ->
            val disablingPolicy = appliedPolicies
                .firstOrNull { it.matched && it.affectedModules?.contains(ruleId) == true }
            val policyLabel = disablingPolicy?.displayLabel ?: "policy"
            SkippedRuleInfo(ruleId, "disabled by policy '$policyLabel'", SkipCategory.DISABLED_BY_POLICY)
        }
    }

    /** Pass 2: SUPPLEMENT/FALLBACK rules, run with the pass-1 findings as [ValidationContext]. */
    private suspend fun executePass2(
        runtimeConfig: Config,
        pass2RuleIds: List<String>,
        mr: MergeRequest,
        pass1Findings: List<Finding>,
        skippedRules: MutableList<SkippedRuleInfo>,
    ): List<Finding> {
        val validationContext = ValidationContext(priorFindings = pass1Findings)

        val pass2WhenResult = policyApplicator.filterByWhen(runtimeConfig, pass2RuleIds, mr, pass1Findings)
        skippedRules.addAll(whenConditionSkips(pass2RuleIds, pass2WhenResult))
        val pass2RuntimeConfig = pass2WhenResult.runtimeConfig
        val pass2Skipped = pass2RuleIds.size - pass2WhenResult.activeRuleIds.size
        if (pass2Skipped > 0) logger.debug("Pass 2: $pass2Skipped rule(s) skipped by 'when' condition")

        val coveredConcernAreas = collectCoveredConcernAreas(runtimeConfig, pass1Findings)
        val gatedPass2 = pass2WhenResult.activeRuleIds.filter { ruleId ->
            val meta = pass2RuntimeConfig.rules.metaFor(ruleId)
            if (meta.mode == RuleMode.FALLBACK && meta.concernArea != null) {
                val dominated = meta.concernArea in coveredConcernAreas
                if (dominated) {
                    logger.debug("Fallback rule '$ruleId': skipped — concern area '${meta.concernArea}' covered by pass-1")
                    skippedRules.add(
                        SkippedRuleInfo(
                            ruleId,
                            "concern area '${meta.concernArea}' covered by pass-1",
                            SkipCategory.CONCERN_AREA_COVERED,
                        ),
                    )
                }
                !dominated
            } else {
                true
            }
        }

        val (pass2ActiveRuleIds, pass2EnrichmentSkips) = filterByRuleRequirements(pass2RuntimeConfig, gatedPass2)
        skippedRules.addAll(pass2EnrichmentSkips)

        return whenErrorFindings(pass2WhenResult) +
            executeRuleGroups(
                pass2RuntimeConfig,
                pass2ActiveRuleIds,
                mr,
                validationContext,
                skippedRules,
            )
    }

    /**
     * Drops findings in each rule's `excludePaths`, deduplicates, truncates to the findings limit and sorts by
     * rule priority, severity and rule ID.
     */
    private fun finalizeFindings(
        runtimeConfig: Config,
        allFindings: MutableList<Finding>,
    ): List<Finding> {
        allFindings.removeAll { excludedByPath(runtimeConfig, it) }
        val beforeDedup = allFindings.size
        val dedupedFindings = allFindings.deduplicated()
        allFindings.clear()
        allFindings.addAll(dedupedFindings)
        if (beforeDedup != dedupedFindings.size) logger.debug("Deduplication: $beforeDedup → ${dedupedFindings.size} findings")

        truncateFindings(allFindings)

        return allFindings.sortedWith(
            compareBy(
                { runtimeConfig.rules.metaFor(it.rule).priority },
                { it.severity.ordinal },
                { it.rule },
            ),
        )
    }

    /** `true` when [finding] sits in a file matching one of its rule's `excludePaths` globs. */
    private fun excludedByPath(
        runtimeConfig: Config,
        finding: Finding,
    ): Boolean {
        val path = (finding.location as? Location.FileRef)?.path ?: return false
        return runtimeConfig.rules
            .metaFor(finding.rule)
            .excludePaths
            .any { GlobMatcher.matches(path, it) }
    }

    // ── Policy application ──────────────────────────────────────────────

    // ── Rule group execution ────────────────────────────────────────────

    private suspend fun executeRuleGroups(
        runtimeConfig: Config,
        ruleIds: List<String>,
        mr: MergeRequest,
        context: ValidationContext?,
        skippedRules: MutableList<SkippedRuleInfo>,
    ): List<Finding> {
        val findings = mutableListOf<Finding>()

        val groups = ruleIds
            .groupBy { ruleId -> runtimeConfig.rules.metaFor(ruleId).priority }
            .entries
            .sortedBy { it.key }

        var bail = false

        for ((_, groupRuleIds) in groups) {
            if (bail) {
                logger.debug("Bail-on-fail: skipping ${groupRuleIds.size} rule(s) in priority group")
                skippedRules.addAll(
                    groupRuleIds.map {
                        SkippedRuleInfo(it, "bail-on-fail triggered", SkipCategory.BAIL_ON_FAIL)
                    },
                )
                continue
            }

            val groupFindings: List<Finding> = coroutineScope {
                groupRuleIds
                    .map { ruleId ->
                        async(Dispatchers.Default) {
                            executeRule(runtimeConfig, ruleId, mr, context)
                        }
                    }.awaitAll()
                    .flatten()
            }

            findings.addAll(groupFindings)

            val hasBailRule = groupRuleIds.any { runtimeConfig.rules.metaFor(it).bailOnFail }
            if (hasBailRule && groupFindings.any { it.severity == Severity.ERROR }) {
                logger.debug("Bail-on-fail triggered: ERROR-severity finding in bail-group")
                bail = true
            }
        }

        return findings
    }

    private suspend fun executeRule(
        runtimeConfig: Config,
        ruleId: String,
        mr: MergeRequest,
        context: ValidationContext?,
    ): List<Finding> {
        val module = registry.findRuleHandler(ruleId)
            ?: run {
                logger.debug("Rule [$ruleId]: no handler registered, skipping")
                return emptyList()
            }
        val configs = configExtractor?.invoke(runtimeConfig, ruleId)
            ?: run {
                logger.warn("Rule [$ruleId]: no config extracted, skipping")
                return emptyList()
            }
        val canonical = registry.canonicalRuleId(ruleId)

        logger.debug("Rule [$canonical]: starting")
        val startOffsetNs = engineStartMark.elapsedNow().inWholeNanoseconds
        val timeout = ruleTimeout(module)
        val (result, duration) = measureTimedValue {
            withRuleTimeout(timeout) {
                ruleResults.cachedValidate(module, canonical, mr, runtimeConfig, configs, context) {
                    executeRuleStandard(module, canonical, mr, configs, context)
                }
            }
        }
        val (raw, fromCache) = result ?: return ruleTimedOut(canonical, checkNotNull(timeout))
        val endOffsetNs = engineStartMark.elapsedNow().inWholeNanoseconds
        logRuleDuration(canonical, duration.inWholeNanoseconds, raw.size)

        val enriched = enrichFindings(runtimeConfig, raw, ruleId)
        val fingerprinted = AnchorFingerprint.stamp(autoFingerprint(enriched), mr.changes)

        collectedResults.update {
            it + RuleExecutionResult(
                ruleId = canonical,
                findings = raw,
                fromCache = fromCache,
                executionDurationNs = duration.inWholeNanoseconds,
                startOffsetNs = startOffsetNs,
                endOffsetNs = endOffsetNs,
            )
        }

        return fingerprinted
    }

    /**
     * Per-rule timeout: the larger of `timeouts.ruleExecution` and the module's own
     * [io.karandash.api.module.RuleModule.executionTimeout] (e.g. `ai.executionTimeout`), or whichever one is set.
     * Null (no timeout, today's behavior) when neither is set; non-positive values count as unset.
     */
    private fun ruleTimeout(module: io.karandash.api.module.RuleModule): Duration? {
        val base = config.timeouts
            ?.ruleExecution
            ?.toKotlinDuration()
            ?.takeIf { it.isPositive() }
        val own = module.executionTimeout?.takeIf { it.isPositive() }
        return if (base != null && own != null) maxOf(base, own) else base ?: own
    }

    /** Bounds [block] by [timeout] (null = unbounded); null means it timed out. Not `withTimeout`: that throws a cancellation. */
    private suspend fun <T : Any> withRuleTimeout(
        timeout: Duration?,
        block: suspend () -> T,
    ): T? = if (timeout == null) block() else withTimeoutOrNull(timeout) { block() }

    /** A timed-out rule contributes no findings of its own, only one engine warning (never cached). */
    private fun ruleTimedOut(
        canonical: String,
        timeout: Duration,
    ): List<Finding> {
        val message = "Rule '$canonical' timed out after $timeout; its findings are missing from this run."
        logger.warn(message)
        return listOf(Finding(severity = Severity.WARNING, message = message, rule = ENGINE_RULE_ID))
    }

    private fun logRuleDuration(
        canonical: String,
        durationNs: Long,
        findingCount: Int,
    ) {
        val durationUs = durationNs / NS_PER_US
        val msWhole = durationUs / US_PER_MS
        val msFrac = (durationUs % US_PER_MS) / (US_PER_MS / 100)
        val msStr = if (durationUs < US_PER_MS) {
            "$msWhole.${msFrac.toString().padStart(2, '0')}ms"
        } else {
            "${msWhole}ms"
        }
        logger.debug("Rule [$canonical]: completed in $msStr -- $findingCount finding(s)")
    }

    private suspend fun executeRuleStandard(
        module: io.karandash.api.module.RuleModule,
        canonical: String,
        mr: MergeRequest,
        configs: List<JsonElement>,
        context: ValidationContext?,
    ): List<Finding> =
        coroutineScope {
            configs
                .map { ruleConfig ->
                    async {
                        if (module is FindingsAwareRuleModule && context != null) {
                            module.validateWithFindings(canonical, mr, ruleConfig, context)
                        } else if (module is FileGranularRuleModule) {
                            mr.changes.flatMap { file ->
                                ruleResults.cachedFile(module, canonical, file, ruleConfig) {
                                    module.validateFile(canonical, file, mr, ruleConfig)
                                }
                            }
                        } else if (run != null) {
                            module.validate(canonical, mr, ruleConfig, run)
                        } else {
                            module.validate(canonical, mr, ruleConfig)
                        }
                    }
                }.awaitAll()
                .flatten()
        }

    // ── Finding enrichment ──────────────────────────────────────────────

    /**
     * Applies user-configured [RuleExecutionMeta.suggestion] and [RuleExecutionMeta.explanation]
     * overrides to findings. User config takes priority; validator defaults are kept when no
     * user override is present.
     */
    private fun enrichFindings(
        runtimeConfig: Config,
        findings: List<Finding>,
        ruleId: String,
    ): List<Finding> {
        val meta = runtimeConfig.rules.metaFor(ruleId)
        if (meta.suggestion == null && meta.explanation == null && meta.severity == null) return findings
        return findings.map { finding ->
            finding.copy(
                suggestion = meta.suggestion ?: finding.suggestion,
                explanation = meta.explanation ?: finding.explanation,
                severity = meta.severity?.let { Severity.fromString(it, finding.severity) } ?: finding.severity,
            )
        }
    }

    // ── Auto-fingerprinting ──────────────────────────────────────────────

    /**
     * Assigns fingerprints to findings that don't already have one.
     * Uses [FindingFingerprint.generate] for consistent deduplication semantics.
     */
    private fun autoFingerprint(findings: List<Finding>): List<Finding> =
        findings.map { finding ->
            if (finding.fingerprint != null) {
                finding
            } else {
                finding.copy(
                    fingerprint = FindingFingerprint.generate(finding.rule, finding.location, finding.message),
                )
            }
        }

    // ── Concern area filtering ──────────────────────────────────────────

    /**
     * Collects concern areas that produced findings in pass-1.
     * A concern area is "covered" if any pass-1 rule with that concern area produced at least one finding.
     */
    private fun collectCoveredConcernAreas(
        runtimeConfig: Config,
        pass1Findings: List<Finding>,
    ): Set<String> {
        val ruleIdsWithFindings = pass1Findings.map { it.rule }.toSet()
        return ruleIdsWithFindings
            .mapNotNull { ruleId -> runtimeConfig.rules.metaFor(ruleId).concernArea }
            .toSet()
    }

    // ── Enablement check ────────────────────────────────────────────────

    /**
     * Checks whether a rule is enabled based on the resolved enablement map.
     * Falls back to the module's [Module.defaultEnabled] when the rule has no explicit enablement entry.
     */
    private fun isRuleEnabled(
        ruleId: String,
        effectiveEnabled: Map<String, Boolean> = enabledModules,
    ): Boolean {
        val explicit = effectiveEnabled[ruleId]
        if (explicit != null) return explicit
        val module = registry.findRuleHandler(ruleId) ?: return true
        return module.defaultEnabled
    }

    // ── Required-enrichment filtering ───────────────────────────────────

    /**
     * Splits [ruleIds] into rules whose [io.karandash.api.module.RuleModule.requiredEnrichment]
     * IDs are all available in [enrichmentContext], and rules to skip because at least one
     * required ID is missing. Runs sequentially (not inside the parallel rule-execution
     * dispatch) since [SkippedRuleInfo] collection is not safe for concurrent mutation.
     * Rules requiring a merge-request field the platform never fills are skipped first.
     */
    private fun filterByRuleRequirements(
        runtimeConfig: Config,
        ruleIds: List<String>,
    ): Pair<List<String>, List<SkippedRuleInfo>> {
        val (supported, fieldSkips) =
            filterByRequiredMergeRequestFields(ruleIds, registry, platform, logger) { configExtractor?.invoke(runtimeConfig, it).orEmpty() }
        val skipped = fieldSkips.toMutableList()
        val active = supported.filter { ruleId ->
            val module = registry.findRuleHandler(ruleId) ?: return@filter true
            val missing = module.requiredEnrichment.filterNot { enrichmentContext.has(it) }
            if (missing.isEmpty()) {
                true
            } else {
                logger.debug("Rule [$ruleId]: skipped, missing required enrichment: ${missing.joinToString(", ")}")
                skipped.add(
                    SkippedRuleInfo(
                        ruleId,
                        "missing required enrichment: ${missing.joinToString(", ")}",
                        SkipCategory.MISSING_REQUIRED_ENRICHMENT,
                    ),
                )
                false
            }
        }
        return active to skipped
    }

    // ── Module skip-reason collection ──────────────────────────────────

    /**
     * Rule handlers implementing [SkipReasonProvider], once each: they report module-internal skip reasons
     * (e.g., AI budget exhaustion, gating) and what they reviewed.
     */
    private fun skipReasonProviders(allRuleIds: List<String>): List<SkipReasonProvider> =
        allRuleIds
            .mapNotNull { registry.findRuleHandler(it) }
            .distinctBy { it.id }
            .filterIsInstance<SkipReasonProvider>()

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun whenConditionSkips(
        ruleIds: List<String>,
        result: WhenFilterResult,
    ): List<SkippedRuleInfo> =
        (ruleIds - result.activeRuleIds.toSet()).map { id ->
            val error = result.whenErrors[id]
            if (error == null) {
                SkippedRuleInfo(id, "when condition not met", SkipCategory.WHEN_CONDITION)
            } else {
                SkippedRuleInfo(id, "when expression failed: $error", SkipCategory.WHEN_ERROR)
            }
        }

    /** One engine warning per rule whose `when:` failed to evaluate, so a broken expression is not a silent skip. */
    private fun whenErrorFindings(result: WhenFilterResult): List<Finding> =
        result.whenErrors.map { (id, error) ->
            Finding(
                severity = Severity.WARNING,
                message = "Rule '$id' did not run: its when expression failed to evaluate: $error",
                rule = ENGINE_RULE_ID,
            )
        }

    private fun truncateFindings(findings: MutableList<Finding>) {
        val maxFindings = config.limits.effectiveMaxFindings
        if (findings.size > maxFindings) {
            val truncated = findings.size - maxFindings
            // keep the most severe findings (stable sort), so a warning flood cannot push an ERROR out.
            val kept = findings.sortedBy { it.severity.ordinal }.take(maxFindings)
            findings.clear()
            findings.addAll(kept)
            findings.add(
                Finding(
                    severity = Severity.WARNING,
                    message = "Findings limit reached ($maxFindings). $truncated additional findings truncated.",
                    rule = ENGINE_RULE_ID,
                ),
            )
        }
    }
}

@Serializable
data class ValidationResult(
    val findings: List<Finding>,
    val skippedRules: List<SkippedRuleInfo> = emptyList(),
    /** Policies that were evaluated during validation. */
    val appliedPolicies: List<ActivePolicy> = emptyList(),
    /** Per-rule execution results for timing reports. */
    val ruleExecutionResults: List<RuleExecutionResult> = emptyList(),
    /** What the run actually reviewed, from [SkipReasonProvider.reviewProvenance]. */
    val provenance: ReviewProvenance? = null,
) {
    /**
     * True when no [Severity.ERROR] findings were produced.
     * Computed from [findings] -- not a constructor parameter.
     */
    val passed: Boolean get() = findings.none { it.severity == Severity.ERROR }
}
