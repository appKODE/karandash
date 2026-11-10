@file:OptIn(ExperimentalSerializationApi::class)

package io.karandash.enrichment.confluence

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
internal data class ConfluencePagesSubConfig(
    val enabled: Boolean = true,
    @SerialName("max-pages-per-mr") @JsonNames("maxPagesPerMr") val maxPagesPerMr: Int = DEFAULT_MAX_PAGES,
    @SerialName("max-chars-per-page") @JsonNames("maxCharsPerPage") val maxCharsPerPage: Int = DEFAULT_MAX_CHARS,
) {
    companion object {
        const val DEFAULT_MAX_PAGES = 5
        const val DEFAULT_MAX_CHARS = 4000
    }
}

@Serializable
internal data class ConfluenceConfig(
    val url: String = "",
    val token: String? = null,
    val email: String? = null,
    val pages: ConfluencePagesSubConfig = ConfluencePagesSubConfig(),
)
