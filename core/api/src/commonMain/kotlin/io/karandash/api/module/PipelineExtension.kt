package io.karandash.api.module

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.Module
import io.karandash.api.platform.FetchOptions
import io.karandash.logger.Logger
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import kotlinx.serialization.json.JsonElement

/**
 * Extension point for the validation pipeline.
 *
 * Pipeline extensions are discovered via [ModuleType.PIPELINE_EXTENSION] and invoked
 * at well-defined points in the validation lifecycle. This allows domain-specific
 * logic (e.g. AI filtering, enrichment, hooks) to be encapsulated in a module
 * rather than hardcoded in the generic pipeline.
 *
 * ## Lifecycle
 *
 * 1. [onConfigured] - called once after the MR is fetched to supply runtime deps, including the provider id
 * 2. [onPostFetch] - called after the MR is fetched from the provider (after [onBaseFiles], when asked for)
 * 3. [afterValidation] - called after validation engine runs, before reporting
 * 4. [beforeReporting] - called just before reporters execute
 * 5. [onPipelineComplete] - called once at the end of the run (also when it fails), for cleanup and summaries
 *
 * A hook that throws is logged as a warning and skipped (its input passes through unchanged); it does
 * not abort the run.
 *
 * Extensions are initialized like any other module via [Module.initialize].
 * Multiple extensions may be registered; they execute in [Module.defaultPriority] order.
 */
public interface PipelineExtension : Module {
    /**
     * Called once after config loading, before any lifecycle hook, with the run's runtime
     * dependencies (AI service, raw config, stats directory), so extensions can build internal
     * helpers (hook executors, enrichers, etc.).
     *
     * Default: no-op.
     */
    @ExperimentalKarandashApi
    public suspend fun onConfigured(env: PipelineEnvironment) {
        // default no-op
    }

    /**
     * Called after the MR is fetched from the provider.
     *
     * @param mr The fetched merge request.
     * @param config Raw top-level config element for extension-specific settings.
     */
    public suspend fun onPostFetch(
        mr: MergeRequest,
        config: JsonElement?,
    ) {
        // default no-op
    }

    /**
     * Called after validation completes. May filter, enrich, or augment findings.
     *
     * @param findings Validation findings from the engine and any scripts/dangerfiles.
     * @param mr The merge request being validated.
     * @param config Raw top-level config element for extension-specific settings.
     * @return Possibly modified findings list.
     */
    public suspend fun afterValidation(
        findings: List<Finding>,
        mr: MergeRequest,
        config: JsonElement?,
    ): List<Finding> = findings

    /**
     * Called just before reporters execute. Last chance to transform findings.
     *
     * The returned list is what reporters show (it also carries findings restored from the incremental cache),
     * not the run's result: findings added or removed here do not change the final `ValidationResult`, the exit
     * code, auto-approve or auto-fix. That keeps presentation-only findings (digests, report notes) from failing
     * a gate. To add a finding that counts, return it from [afterValidation].
     *
     * @param findings Findings after all post-validation processing.
     * @param mr The merge request being validated.
     * @return Possibly modified findings list.
     */
    public suspend fun beforeReporting(
        findings: List<Finding>,
        mr: MergeRequest,
    ): List<Finding> = findings

    /**
     * Called once at the end of the run, after reporting, auto-approve and auto-fix, and also when the
     * run fails after [onConfigured]. Use for cleanup, cost summaries, statistics.
     */
    public suspend fun onPipelineComplete() {
        // default no-op
    }

    /**
     * Returns a human-readable cost or resource summary, or null if not applicable.
     * Called once by the pipeline after every [onPipelineComplete] has run, and logged at `info` level.
     * Implementations that also log the summary elsewhere (e.g. on shutdown) should return it only once.
     */
    public fun costSummary(): String? = null

    /**
     * Hints for how the platform provider should fetch the MR (e.g. diff context lines), derived
     * from the extension's top-level config block [config]. Null (default) means no preference.
     * The pipeline uses the first non-null answer.
     */
    @ExperimentalKarandashApi
    public fun fetchOptions(config: JsonElement?): FetchOptions? = null

    /**
     * Whether this extension reads `FileChange.blameData`, given its top-level config block [config].
     * When any extension answers true the pipeline fills blame for modified and deleted files after the
     * fetch, via [PlatformServiceModule.fetchFileBlame]. Default false: no blame calls.
     */
    @ExperimentalKarandashApi
    public fun needsBlame(config: JsonElement?): Boolean = false

    /**
     * The configured rule ids among [ruleIds] (keys of the top-level `rules:` block) that this extension keeps
     * from running, given its top-level config block [config] (e.g. an execution profile's `excludeRules`). The
     * pipeline drops them before modules are initialized, so they do not run at all. Default: none.
     */
    @ExperimentalKarandashApi
    public fun excludedRules(
        config: JsonElement?,
        ruleIds: Set<String>,
    ): Set<String> = emptySet()

    /**
     * Whether this extension reads `FileChange.newContent`, given its top-level config block [config].
     * When any extension answers true the pipeline fills head content for added and modified files after the
     * fetch, via [PlatformServiceModule.fetchFileContent]. Default false: no content calls.
     */
    @ExperimentalKarandashApi
    public fun needsHeadContent(config: JsonElement?): Boolean = false

    /**
     * Repository paths this extension reads from the MR base (the target), given its top-level config block
     * [config]. The pipeline reads them after the fetch, via [PlatformServiceModule.fetchFileContent] at
     * `diffRefs.baseSha` (else the target branch), never the MR head or the working tree, and hands them to
     * [onBaseFiles]. Use it for files the MR author must not be able to change for their own run. Default: none.
     */
    @ExperimentalKarandashApi
    public fun baseFiles(config: JsonElement?): Set<String> = emptySet()

    /**
     * Receives the [baseFiles] read from the MR base, keyed by path, before [onPostFetch]. A path missing from
     * [files] was absent at the base, could not be read, or the platform cannot read files. Called only when
     * [baseFiles] is not empty. Default: no-op.
     */
    @ExperimentalKarandashApi
    public suspend fun onBaseFiles(files: Map<String, String>) {
        // default no-op
    }

    // -- Incremental cache hooks ------------------------------------------------

    /**
     * Returns cached findings when the MR is fully unchanged since the last run.
     *
     * Extensions that support incremental validation (e.g. AI pipeline) can
     * compare the current MR against a persisted snapshot and return cached
     * findings when nothing has changed. The pipeline then skips the rules of modules with
     * [RuleModule.usesExtensionComputedConfig] (e.g. AI) and reuses these findings for them; every
     * other rule still runs, and cached findings of rules that ran are replaced by fresh ones.
     *
     * @param mr The merge request being validated.
     * @param config Raw top-level config element.
     * @param logger Pipeline logger for diagnostics.
     * @return Cached findings if the MR is fully unchanged, or null to proceed with validation.
     */
    public fun getCachedFindings(
        mr: MergeRequest,
        config: JsonElement?,
        logger: Logger,
    ): List<Finding>? = null

    /**
     * Drops any cached results this extension holds for [ruleIds] (`--invalidate-rules`).
     *
     * Called before validation, only when at least one rule is invalidated. While rules are
     * invalidated the pipeline also skips the [getCachedFindings] short-circuit, so they re-run.
     */
    public suspend fun invalidateRules(ruleIds: Set<String>) {
        // Default: nothing cached per rule.
    }

    /**
     * Persists incremental cache state after validation completes.
     *
     * Called after all post-processing so the extension can store the final
     * findings and MR snapshot for use in subsequent runs.
     *
     * @param mr The merge request that was validated.
     * @param findings The final findings to cache.
     */
    public fun saveIncrementalState(
        mr: MergeRequest,
        findings: List<Finding>,
    ) {
        // default no-op
    }

    // -- Incremental cache query ------------------------------------------------

    /**
     * Returns the set of file paths that were re-reviewed in this pipeline run
     * (changed since the last cached run), or null if incremental caching is
     * not active for this extension.
     *
     * Used by the pipeline to scope inline comment resolution and posting to
     * only the changed files, leaving comments on unchanged files untouched.
     *
     * - null: no cache context - resolve and post all inline comments (current behavior)
     * - empty set: full cache hit - skip inline resolution and posting entirely
     * - non-empty set: partial cache hit - only resolve/post for these files
     */
    public fun incrementalChangedFiles(): Set<String>? = null

    /**
     * Returns cached findings from unchanged files in the incremental cache,
     * or null if incremental caching is not active.
     *
     * Used by the pipeline to merge cached findings into the reporter's finding
     * list so the summary comment shows the complete picture (fresh + cached).
     * This is called AFTER [saveIncrementalState] and BEFORE [beforeReporting].
     */
    public fun incrementalCachedFindings(): List<Finding>? = null

    // -- Rule module config ----------------------------------------------------

    /**
     * Computes the plugin config for the extension's rule module from
     * the raw top-level config element.
     *
     * Used by the pipeline to early-initialize rule modules contributed by
     * this extension without importing extension-specific config classes.
     *
     * @param configElement Raw config element (e.g. the `ai:` JSON block).
     * @return A config element suitable for [Module.initialize] via [PluginContext], or null.
     */
    public fun computeRuleModuleConfig(configElement: JsonElement?): JsonElement? = null
}
