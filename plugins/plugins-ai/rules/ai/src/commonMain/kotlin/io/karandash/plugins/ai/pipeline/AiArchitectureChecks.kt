@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.pipeline

import io.karandash.api.exception.KarandashException
import io.karandash.api.finding.FindingHashChain
import io.karandash.api.run.RunScopedServices
import io.karandash.api.run.RunServiceKeys
import io.karandash.api.support.rethrowCancellation
import io.karandash.config.keys.PolicyKeys
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.LocalizedString
import io.karandash.models.finding.Severity
import io.karandash.models.finding.withText
import io.karandash.plugins.ai.core.architecture.CrossMrDependencyAnalyzer
import io.karandash.plugins.ai.core.architecture.PolicyDefinitions
import io.karandash.plugins.ai.core.architecture.PolicyIssueSeverity
import io.karandash.plugins.ai.core.architecture.PolicyVerifier
import io.karandash.plugins.ai.core.architecture.RuleMutationTester
import io.karandash.plugins.ai.core.architecture.SemanticDiffChecker
import io.karandash.sdk.Logger
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.days

/** Appends the findings of the `ai.architecture.*` checks that are switched on. */
internal class AiArchitectureChecks(
    private val logger: Logger,
) {
    /**
     * `ai.mutationTesting`: validates each [RuleMutationTester] synthetic bad MR with the run's non-AI rules and
     * reports the mutation score plus one finding per mutation no rule caught.
     */
    suspend fun mutationTesting(options: AiArchitectureOptions): List<Finding> {
        if (!options.mutationTesting) return emptyList()
        val validator = RunScopedServices.current()?.get(RunServiceKeys.RULE_VALIDATOR)
        if (validator == null) {
            logger.warn("ai.mutationTesting: no rule validator in this run, skipping")
            return emptyList()
        }
        val mutations = RuleMutationTester.generateMutations()
        // runTests takes a non-suspend validator, so validate every mutation MR up front.
        val findingsByMr = mutations.associate { it.mr to validator.validate(it.mr) }
        val result = RuleMutationTester.runTests(mutations) { findingsByMr[it].orEmpty() }
        val percent = (result.mutationScore * PERCENT).roundToInt()
        val score = ArchitectureCheckText.MUTATION_SCORE.of(percent, result.caughtCount, result.totalMutations)
        return listOf(finding(Severity.INFO, score, MUTATION_TESTING_RULE)) +
            result.outcomes.filterNot { it.caught }.map {
                val text = ArchitectureCheckText.MUTATION_ESCAPED.of(it.mutation.name, it.mutation.description, it.mutation.targetRule)
                finding(Severity.INFO, text, MUTATION_TESTING_RULE)
            }
    }

    /**
     * `ai.dependencies`: compares [mr] with its open siblings, listed through the platform API when the run has one
     * ([RunServiceKeys.OPEN_MERGE_REQUESTS]) and otherwise read from the snapshots in [statsDir]; records [mr] there.
     */
    suspend fun crossMrDependencies(
        mr: MergeRequest,
        options: AiArchitectureOptions,
        statsDir: String,
    ): List<Finding> {
        val deps = options.dependencies ?: return emptyList()
        val store = MrSnapshotStore(statsDir, logger)
        val siblings = platformSiblings(mr) ?: store.siblings(mr, deps.detectionWindow.days)
        val analysis = CrossMrDependencyAnalyzer.analyze(mr, siblings)
        store.record(mr)
        val conflicts = if (deps.checkConflicts) analysis.conflicts else emptyList()
        return conflicts.map { conflict ->
            val text = ArchitectureCheckText.MERGE_ORDER.of(conflict.mrTitleB, conflict.mrIdB, conflict.conflictingFiles.joinToString())
            finding(Severity.WARNING, text, CROSS_MR_RULE)
        } + analysis.dependencies.map { dependency ->
            val title = siblings.firstOrNull { it.id == dependency.toMrId }?.title ?: dependency.toMrId
            finding(Severity.WARNING, ArchitectureCheckText.DELETES_FILES.of(title, dependency.affectedFiles.joinToString()), CROSS_MR_RULE)
        }
    }

    private fun finding(
        severity: Severity,
        text: LocalizedString,
        rule: String,
    ): Finding = Finding(severity, text.en, rule).withText(message = text)

    /** Open MRs on [mr]'s target branch from the platform API, [mr] excluded; `null` when unavailable or failing. */
    private suspend fun platformSiblings(mr: MergeRequest): List<MergeRequest>? {
        val lister = RunScopedServices.current()?.get(RunServiceKeys.OPEN_MERGE_REQUESTS) ?: return null
        val failure: Exception = try {
            return lister.list(mr.targetBranch, MAX_PLATFORM_SIBLINGS + 1)?.filter { it.id != mr.id }?.take(MAX_PLATFORM_SIBLINGS)
        } catch (e: KarandashException) {
            e
        } catch (e: IOException) {
            e
        } catch (e: IllegalStateException) {
            e.rethrowCancellation()
            e
        }
        logger.warn("ai.dependencies: listing open MRs failed, using local snapshots: ${failure.message}")
        return null
    }

    /** [rawConfig] is the whole config; `policyVerification` reads its top-level `policies:`. */
    fun apply(
        findings: List<Finding>,
        mr: MergeRequest,
        options: AiArchitectureOptions,
        rawConfig: JsonElement?,
    ): List<Finding> =
        buildList {
            addAll(findings)
            if (options.semanticDiff) addAll(SemanticDiffChecker.check(mr.changes))
            options.policyVerification?.let { addAll(verifyPolicies(rawConfig, it.reportIssues)) }
        }

    private fun verifyPolicies(
        rawConfig: JsonElement?,
        reportIssues: Boolean,
    ): List<Finding> {
        val policies = (rawConfig as? JsonObject)?.get(PolicyKeys.POLICIES) as? JsonArray ?: return emptyList()
        val issues = PolicyVerifier.verify(PolicyDefinitions.from(policies)).issues
        if (!reportIssues) {
            issues.forEach { logger.warn("Policy verification: [${it.severity}] ${it.message}") }
            return emptyList()
        }
        return issues.map { issue ->
            val severity = if (issue.severity == PolicyIssueSeverity.ERROR) Severity.WARNING else Severity.INFO
            Finding(severity, issue.message, POLICY_VERIFICATION_RULE)
        }
    }

    /**
     * The finding hash chain when `ai.architecture.provenanceChain` is on (null when off); also appended to
     * `provenanceChain.persistChain` when that is set.
     */
    fun persistProvenance(
        findings: List<Finding>,
        mr: MergeRequest,
        config: JsonElement?,
    ): FindingHashChain? {
        // options were already validated (and warned about) in afterValidation
        val options = AiArchitectureOptions.from(config) {}.provenanceChain ?: return null
        val chain = AiReportGenerator.provenance(findings, mr, config)
        options.persistChain?.let { ProvenanceLog.append(it, mr.id, chain, logger) }
        return chain
    }

    companion object {
        /** Open MRs `ai.dependencies` fetches from the platform per run (one full MR fetch each). */
        const val MAX_PLATFORM_SIBLINGS = 20

        /** Rule id of `ai.architecture.policyVerification` findings. */
        const val POLICY_VERIFICATION_RULE = "policy-verification"

        /** Rule id of `ai.mutationTesting` findings. */
        const val MUTATION_TESTING_RULE = "mutation-testing"
        private const val PERCENT = 100

        /** Rule id of `ai.dependencies` findings. */
        const val CROSS_MR_RULE = "cross-mr-dependency"
    }
}
