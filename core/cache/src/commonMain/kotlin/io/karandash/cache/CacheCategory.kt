package io.karandash.cache

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a cache namespace on disk holds, shown by `cache stats` and `cache clear`.
 *
 * @property namespaces The [CacheManager.getCasCache] namespaces whose entries belong to this category.
 */
@Serializable
enum class CacheCategory(
    val namespaces: List<String>,
) {
    @SerialName("provider-responses")
    PROVIDER_RESPONSES(listOf("http-responses")),

    @SerialName("ai-responses")
    AI_RESPONSES(listOf("responses")),

    @SerialName("validation-state")
    VALIDATION_STATE(listOf("state")),

    @SerialName("extends")
    EXTENDS(listOf(CacheManager.DOWNLOADS_NAMESPACE)),

    @SerialName("plugins")
    PLUGINS(listOf(CacheManager.DOWNLOADS_NAMESPACE)),

    /** The Jira enrichment's ticket cache (`JiraEnrichmentProvider.CACHE_NAME`). */
    @SerialName("jira")
    JIRA(listOf("enrichment:jira")),
    ;

    /** Display name, the same as the serialized one. */
    val displayName: String get() = name.lowercase().replace('_', '-')

    companion object {
        /** The categories stored in [namespace]; empty for a namespace no category claims. */
        fun forNamespace(namespace: String): List<CacheCategory> = entries.filter { namespace in it.namespaces }
    }
}
