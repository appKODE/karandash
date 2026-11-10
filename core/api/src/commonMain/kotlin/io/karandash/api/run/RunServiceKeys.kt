package io.karandash.api.run

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.cache.CacheProvider
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.module.PipelineExtension
import io.karandash.api.platform.MergeRequestField
import io.karandash.api.platform.SubprocessBudget
import io.karandash.logger.Logger
import io.karandash.models.support.PatternAwareCompiler

/** Run-scoped service keys owned by `core/api`. */
@ExperimentalKarandashApi
public object RunServiceKeys {
    public val CACHE_PROVIDER: ServiceKey<CacheProvider> = serviceKey("cacheProvider")
    public val SUBPROCESS_BUDGET: ServiceKey<SubprocessBudget> = serviceKey("subprocessBudget")

    /** The run's logger, provided by every run session, so SDK bridges can warn outside a plugin context. */
    public val LOGGER: ServiceKey<Logger> = serviceKey("logger")

    /** The run's pattern compiler: resolves `@name` patterns against this run's `patterns:` only. */
    public val PATTERN_COMPILER: ServiceKey<PatternAwareCompiler> = serviceKey("patternCompiler")

    /** This run's pipeline extensions: PER_RUN extensions are the run's own handles, not the registry prototypes. */
    public val PIPELINE_EXTENSIONS: ServiceKey<List<PipelineExtension>> = serviceKey("pipelineExtensions")

    /** Re-runs this run's non-extension rules on another MR, e.g. a synthetic one for `ai.mutationTesting`. */
    public val RULE_VALIDATOR: ServiceKey<MergeRequestValidator> = serviceKey("ruleValidator")

    /** Lists the run project's open MRs through the platform API, e.g. for `ai.dependencies`; absent offline. */
    public val OPEN_MERGE_REQUESTS: ServiceKey<OpenMergeRequestLister> = serviceKey("openMergeRequests")

    /**
     * Bodies of this run's inline comments a reviewer dismissed (`PlatformServiceModule.listDismissedInlineComments`
     * searched for `InlineFindingMarker.PREFIX`), e.g. for `ai.feedback`; absent offline, `null` without a signal.
     */
    public val DISMISSED_INLINE_COMMENTS: ServiceKey<DismissedInlineComments> = serviceKey("dismissedInlineComments")

    /** The run MR's review threads, e.g. for `ai.feedback` to learn from replies and thread commands; absent offline. */
    public val REVIEW_THREADS: ServiceKey<MergeRequestThreads> = serviceKey("reviewThreads")

    /** Searches the run project's MRs by text or author, e.g. for `ai.similar-issues`; absent offline. */
    public val MERGE_REQUEST_SEARCH: ServiceKey<MergeRequestSearch> = serviceKey("mergeRequestSearch")

    /** Reads a repository file at a ref through the platform, e.g. CODEOWNERS on the target branch; absent offline. */
    public val REPOSITORY_FILE: ServiceKey<RepositoryFileReader> = serviceKey("repositoryFile")

    /** The run's published enrichment, for formats and outputs that run after enrichment; absent until it is published. */
    public val ENRICHMENT: ServiceKey<EnrichmentContext> = serviceKey("enrichment")

    /** Merge-request fields the run's platform never fills (`PlatformServiceModule.unavailableMergeRequestFields`). */
    public val UNAVAILABLE_FIELDS: ServiceKey<Set<MergeRequestField>> = serviceKey("unavailableFields")

    /** Saves layer inputs and outputs as files (`validate --dump-dir`); absent unless a dump directory is set. */
    public val DUMP: ServiceKey<RunDump> = serviceKey("dump")
}
