package io.karandash.config

import kotlinx.serialization.Serializable

/**
 * Configuration for the pre-validation enrichment stage.
 *
 * When enabled, an AI model generates a suggested title and/or description for the MR
 * before validation runs. In non-dry-run mode the suggestions are applied via the
 * mutator, and the MR is re-fetched so downstream validation sees the enriched data.
 */
@Serializable
data class EnrichmentConfig(
    val generateDescription: Boolean = false,
    val generateTitle: Boolean = false,
    val skipIfDescriptionExists: Boolean = true,
    val model: String? = null,
    /** Response token limit of the generation calls; null leaves the limit the generating rule would use anyway. */
    val maxTokens: Int? = null,
) {
    companion object {
        const val DEFAULT_MAX_TOKENS = 2000
    }
}
