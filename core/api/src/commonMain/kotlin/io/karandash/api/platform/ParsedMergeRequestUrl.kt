package io.karandash.api.platform

import kotlinx.serialization.Serializable

@Serializable
public data class ParsedMergeRequestUrl(
    val platform: String,
    val baseUrl: String,
    val projectId: String,
    val mrId: String
)
