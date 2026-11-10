package io.karandash.detect

import io.karandash.api.PluginContext
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.CIEnvironment
import io.karandash.api.platform.ParsedMergeRequestUrl
import io.karandash.api.platform.ProviderDetectionScore
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest

/** A platform service whose detection answers are fixed; records the URLs it was asked to parse. */
internal class FakePlatformService(
    override val id: String,
    private val aliases: Set<String> = emptySet(),
    private val apiUrl: String? = null,
    private val ci: CIEnvironment? = null,
    private val score: Double? = null,
    private val urlPrefix: String? = null,
    private val reportedPlatform: String = id,
    private val remoteHost: String? = null,
) : PlatformServiceModule {
    val parsedUrls = mutableListOf<String>()
    override val name = id
    override val version = "1.0"

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun fetchMergeRequest(
        projectId: String,
        mrId: String,
    ): MergeRequest = unused()

    override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = unused()

    override suspend fun addLabels(
        projectId: String,
        mrId: String,
        labels: List<String>,
    ) = unused()

    override suspend fun removeLabels(
        projectId: String,
        mrId: String,
        labels: List<String>,
    ) = unused()

    override suspend fun setDraft(
        projectId: String,
        mrId: String,
        draft: Boolean,
    ) = unused()

    override suspend fun updateTitle(
        projectId: String,
        mrId: String,
        title: String,
    ) = unused()

    override suspend fun updateDescription(
        projectId: String,
        mrId: String,
        description: String,
    ) = unused()

    override suspend fun approve(
        projectId: String,
        mrId: String,
    ) = unused()

    override suspend fun unapprove(
        projectId: String,
        mrId: String,
    ) = unused()

    override suspend fun postComment(
        projectId: String,
        mrId: String,
        body: String,
    ): String = unused()

    override suspend fun updateComment(
        projectId: String,
        mrId: String,
        commentId: String,
        body: String,
    ) = unused()

    override suspend fun findExistingComment(
        projectId: String,
        mrId: String,
        marker: String,
    ): String? = unused()

    override suspend fun postInlineComment(
        projectId: String,
        mrId: String,
        body: String,
        filePath: String,
        line: Int,
        diffRefs: DiffRefs,
        startLine: Int?,
    ): String = unused()

    override suspend fun aliases(): Set<String> = aliases

    override suspend fun defaultApiUrl(): String? = apiUrl

    override suspend fun detectCiEnvironment(env: (String) -> String?): CIEnvironment? = ci

    override suspend fun detectConfidence(env: (String) -> String?): ProviderDetectionScore? =
        score?.let { ProviderDetectionScore(providerId = id, score = it, source = "test:$id", ciEnvironment = ci) }
            ?: super.detectConfidence(env)

    override suspend fun parseMergeRequestUrl(url: String): ParsedMergeRequestUrl? {
        parsedUrls += url
        val prefix = urlPrefix ?: return null
        if (!url.startsWith(prefix)) return null
        return ParsedMergeRequestUrl(platform = reportedPlatform, baseUrl = prefix, projectId = "group/project", mrId = "7")
    }

    override suspend fun parseGitRemoteUrl(url: String): String? =
        remoteHost?.takeIf { it in url }?.let { url.substringAfter("$it:").substringAfter("$it/").removeSuffix(".git") }

    private fun unused(): Nothing = error("detection tests never call MR operations")
}
