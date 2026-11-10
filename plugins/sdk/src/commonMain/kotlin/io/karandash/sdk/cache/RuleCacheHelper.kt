package io.karandash.sdk.cache

import io.karandash.api.cache.KarandashCache
import io.karandash.models.finding.Finding
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.builtins.ListSerializer

/**
 * Helper for dependency-aware rule result caching.
 *
 * Rule modules call [cachedValidate] in their `validate()` method to
 * automatically cache findings based on the MR data they depend on.
 * When dependencies haven't changed, cached findings are returned
 * without re-executing the rule.
 *
 * Usage in a rule module:
 * ```kotlin
 * override suspend fun validate(ruleId: String, mr: MergeRequest, configElement: JsonElement): List<Finding> {
 *     return RuleCacheHelper.cachedValidate(
 *         cache = cache,
 *         ruleId = ruleId,
 *         dependencies = mapOf("title" to mr.title, "config" to configElement.toString()),
 *     ) {
 *         // actual validation logic
 *     }
 * }
 * ```
 */
public object RuleCacheHelper {
    private val findingsSerializer = ListSerializer(Finding.serializer())

    /**
     * Returns cached findings if [dependencies] are unchanged, otherwise runs [validate].
     *
     * @param cache The rule's cache instance, or null if caching is disabled.
     * @param ruleId The rule identifier (used as cache key).
     * @param dependencies Map of dependency names to their current string values.
     * @param validate Suspend function that produces findings when cache is stale.
     * @return Cached or freshly computed findings.
     */
    public suspend fun cachedValidate(
        cache: KarandashCache?,
        ruleId: String,
        dependencies: Map<String, String>,
        validate: suspend () -> List<Finding>,
    ): List<Finding> {
        if (cache == null) return validate()
        val cached = cache.getOrPut(ruleId, dependencies) {
            KarandashJson.Default.encodeToString(findingsSerializer, validate())
        }
        return KarandashJson.Default.decodeFromString(findingsSerializer, cached)
    }
}
