package io.karandash.enrichment.confluence

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Raw REST API response from `GET /rest/api/content/{id}?expand=body.storage,version`. */
@Serializable
internal data class ConfluencePageResponse(
    val id: String = "",
    val title: String = "",
    val version: ConfluenceVersion = ConfluenceVersion(),
    val body: ConfluencePageBody = ConfluencePageBody(),
    @SerialName("_links") val links: ConfluencePageLinks? = null,
)

@Serializable
internal data class ConfluenceVersion(
    val number: Int = 0,
)

@Serializable
internal data class ConfluencePageBody(
    val storage: ConfluenceStorageView? = null,
)

@Serializable
internal data class ConfluenceStorageView(
    val value: String = "",
)

@Serializable
internal data class ConfluencePageLinks(
    val webui: String? = null,
)

/** Domain model stored in [EnrichmentContext] under `confluence:pages`. */
@Serializable
data class ConfluencePageContent(
    val pageId: String,
    val title: String,
    val version: Int,
    val markdown: String,
    val sourceUrl: String,
)
