package io.karandash.engine

import io.karandash.Version
import io.karandash.api.cache.KarandashCache
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.context.ValidationContext
import io.karandash.api.module.RuleModule
import io.karandash.config.Config
import io.karandash.logger.Logger
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.cache.RuleCacheHelper
import io.karandash.utils.EnrichmentGlobMatcher
import kotlinx.atomicfu.atomic
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Rule-result cache used by [ValidationEngine].
 *
 * Results are stored under `rule:<canonical id>` via [RuleCacheHelper.cachedValidate], keyed on the
 * serialized MR, the rule's config elements, the whole resolved [Config] (a rule may read global blocks such as `ai`),
 * (for pass-2 rules) the pass-1 [ValidationContext], the enrichment data the rule's module receives and the
 * implementation (module id and version, karandash version), so an upgraded or replaced rule never serves
 * findings computed by the old code. A rule is served from cache only when all of them are unchanged.
 *
 * @param cache Backing cache, or null when caching is disabled (`--no-cache`).
 * @param enrichment This run's enrichment data; the part a module's patterns match is a dependency of its rules.
 * @param enrichmentBindings `rule-enrichment:` patterns by rule id.
 * @param invalidatedRules Canonical rule ids (`--invalidate-rules`) whose cached results are dropped and recomputed.
 *
 * @lifecycle per-run -- do not reuse across invocations
 */
internal class RuleResultCache(
    private val cache: KarandashCache?,
    private val logger: Logger,
    private val enrichment: EnrichmentContext = EnrichmentContext.EMPTY,
    private val enrichmentBindings: Map<String, List<String>> = emptyMap(),
    private val invalidatedRules: Set<String> = emptySet(),
) {
    private val lastMr = atomic<Pair<MergeRequest, String>?>(null)
    private val lastConfig = atomic<Pair<Config, String>?>(null)

    /** Runs [validate] unless a cached result for [ruleId] matches; returns the findings and whether they were cached. */
    suspend fun cachedValidate(
        module: RuleModule,
        ruleId: String,
        mr: MergeRequest,
        runtimeConfig: Config,
        configs: List<JsonElement>,
        context: ValidationContext?,
        validate: suspend () -> List<Finding>,
    ): Pair<List<Finding>, Boolean> {
        if (cache == null) {
            logger.debug("Rule cache [$ruleId]: miss (caching disabled)")
            return validate() to false
        }
        var computed = false
        val key = key(ruleId)
        if (ruleId in invalidatedRules) cache.invalidate(key)
        val findings = RuleCacheHelper.cachedValidate(cache, key, dependencies(module, mr, runtimeConfig, configs, context)) {
            computed = true
            validate()
        }
        val reason =
            if (computed) "miss (no entry, or MR/config/context/version changed)" else "hit (MR, config, context and version unchanged)"
        logger.debug("Rule cache [$ruleId]: $reason")
        return findings to !computed
    }

    /**
     * Per-file results of a [io.karandash.api.module.FileGranularRuleModule]: [file] is re-validated only when
     * its change (path, diff, content), [config] or the implementation differs, so an MR edit re-runs the rule on
     * changed files only.
     */
    suspend fun cachedFile(
        module: RuleModule,
        ruleId: String,
        file: FileChange,
        config: JsonElement,
        validate: suspend () -> List<Finding>,
    ): List<Finding> {
        val dependencies = buildMap {
            put("file", KarandashJson.Default.encodeToString(FileChange.serializer(), file))
            put("config", config.toString())
            put("implementation", implementation(module))
            enrichmentJson(module)?.let { put("enrichment", it) }
        }
        val key = fileKey(ruleId, file.path)
        if (cache != null && ruleId in invalidatedRules) cache.invalidate(key)
        return RuleCacheHelper.cachedValidate(cache, key, dependencies, validate)
    }

    /** The enrichment entries [module] receives (see `ModuleInitializer`), or null when it receives none. */
    private suspend fun enrichmentJson(module: RuleModule): String? {
        if (enrichment.availableIds.isEmpty()) return null
        val patterns = EnrichmentGlobMatcher.patternsFor(module.supportedEnrichment, enrichmentBindings, module.contributedRules())
        val ids = EnrichmentGlobMatcher.resolve(patterns, enrichment.availableIds)
        if (ids.isEmpty()) return null
        return JsonObject(ids.sorted().associateWith { checkNotNull(enrichment.get(it)) }).toString()
    }

    private suspend fun dependencies(
        module: RuleModule,
        mr: MergeRequest,
        runtimeConfig: Config,
        configs: List<JsonElement>,
        context: ValidationContext?,
    ): Map<String, String> =
        buildMap {
            put("mr", mrJson(mr))
            put("config", configs.toString())
            put("runtimeConfig", configJson(runtimeConfig))
            put("implementation", implementation(module))
            if (context != null) {
                put("context", KarandashJson.Default.encodeToString(ValidationContext.serializer(), context))
            }
            enrichmentJson(module)?.let { put("enrichment", it) }
        }

    /** Serializes [mr] once per instance; rules of the same run share the result. */
    private fun mrJson(mr: MergeRequest): String {
        lastMr.value?.let { (cached, json) -> if (cached === mr) return json }
        val json = KarandashJson.Default.encodeToString(MergeRequest.serializer(), mr)
        lastMr.value = mr to json
        return json
    }

    /** Serializes [config] once per instance; rules of the same run share the result. */
    private fun configJson(config: Config): String {
        lastConfig.value?.let { (cached, json) -> if (cached === config) return json }
        val json = KarandashJson.Default.encodeToString(Config.serializer(), config)
        lastConfig.value = config to json
        return json
    }

    companion object {
        /** The code that computes a rule's findings: a version bump of the module or of karandash invalidates them. */
        fun implementation(module: RuleModule): String = "${module.id}@${module.version} karandash ${Version.VERSION}"

        /** Cache key for [ruleId]'s results. */
        fun key(ruleId: String): String = "rule:$ruleId"

        /** Cache key for [ruleId]'s results on the file at [path]. */
        fun fileKey(
            ruleId: String,
            path: String,
        ): String = "rule:$ruleId:file:$path"
    }
}
