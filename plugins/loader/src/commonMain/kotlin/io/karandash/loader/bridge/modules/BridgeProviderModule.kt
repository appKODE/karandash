package io.karandash.loader.bridge.modules

import io.karandash.api.ModuleType
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.CIEnvironment
import io.karandash.api.platform.ParsedMergeRequestUrl
import io.karandash.api.support.suspendCatching
import io.karandash.loader.BridgeDeserializer
import io.karandash.loader.bridge.BridgeConstants
import io.karandash.loader.bridge.BridgePart
import io.karandash.loader.bridge.BridgeTransport
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class BridgeProviderModule(
    transport: BridgeTransport
) : BridgePart(transport),
    PlatformServiceModule {
    override val type: ModuleType get() = ModuleType.PLATFORM_SERVICE
    private val json = KarandashJson.Default

    private val mutator by lazy { BridgeMutatorModule(transport) }
    private val poster by lazy { BridgePlatformServiceModule(transport) }

    override suspend fun fetchMergeRequest(
        projectId: String,
        mrId: String
    ): MergeRequest {
        val result = transport.call("fetchMergeRequest", projectId, mrId)
            ?: error("fetchMergeRequest returned null")
        return json.decodeFromString(MergeRequest.serializer(), result)
    }

    override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest {
        val result = transport.call("fetchMergeRequestByUrl", url)
            ?: error("fetchMergeRequestByUrl returned null")
        return json.decodeFromString(MergeRequest.serializer(), result)
    }

    override suspend fun detectCiEnvironment(env: (String) -> String?): CIEnvironment? {
        if (!transport.hasCapability("detectCiEnvironment")) return null
        // ABI: detect_ci_environment(env_json) receives the values of the plugin's own supportedEnvVars.
        val names = suspendCatching { json.decodeFromString<List<String>>(transport.getProperty("supportedEnvVars")) }
            .getOrDefault(emptyList())
        val envJson = buildJsonObject { names.forEach { name -> env(name)?.let { put(name, it) } } }.toString()
        val result = transport.call("detectCiEnvironment", envJson) ?: return null
        return BridgeDeserializer.parseCIEnvironmentFromJson(result)
    }

    override suspend fun parseMergeRequestUrl(url: String): ParsedMergeRequestUrl? {
        if (!transport.hasCapability("parseMergeRequestUrl")) return null
        val result = transport.call("parseMergeRequestUrl", url) ?: return null
        return BridgeDeserializer.parseParsedMergeRequestUrlFromJson(result)
    }

    override suspend fun parseGitRemoteUrl(url: String): String? {
        if (!transport.hasCapability("parseGitRemoteUrl")) return null
        return transport.call("parseGitRemoteUrl", url)?.takeIf { it.isNotBlank() && it != BridgeConstants.NULL_SENTINEL }
    }

    override suspend fun defaultApiUrl(): String? {
        if (!transport.hasCapability("defaultApiUrl")) return null
        return transport.call("defaultApiUrl")?.takeIf { it.isNotBlank() && it != BridgeConstants.NULL_SENTINEL }
    }

    override suspend fun authTokenHint(): String? {
        if (!transport.hasCapability("authTokenHint")) return null
        return transport.call("authTokenHint")?.takeIf { it.isNotBlank() && it != BridgeConstants.NULL_SENTINEL }
    }

    override suspend fun aliases(): Set<String> {
        if (!transport.hasCapability("aliases")) return emptySet()
        val result = transport.call("aliases") ?: return emptySet()
        return suspendCatching { json.decodeFromString<List<String>>(result).toSet() }.getOrDefault(emptySet())
    }

    // The mutation ops go through the guest like on BridgeMutatorModule.

    override suspend fun addLabels(
        projectId: String,
        mrId: String,
        labels: List<String>
    ) = mutator.addLabels(projectId, mrId, labels)

    override suspend fun removeLabels(
        projectId: String,
        mrId: String,
        labels: List<String>
    ) = mutator.removeLabels(projectId, mrId, labels)

    override suspend fun setDraft(
        projectId: String,
        mrId: String,
        draft: Boolean
    ) = mutator.setDraft(projectId, mrId, draft)

    override suspend fun updateTitle(
        projectId: String,
        mrId: String,
        title: String
    ) = mutator.updateTitle(projectId, mrId, title)

    override suspend fun updateDescription(
        projectId: String,
        mrId: String,
        description: String
    ) = mutator.updateDescription(projectId, mrId, description)

    override suspend fun approve(
        projectId: String,
        mrId: String
    ) = mutator.approve(projectId, mrId)

    override suspend fun unapprove(
        projectId: String,
        mrId: String
    ) = mutator.unapprove(projectId, mrId)

    // The comment ops go through the guest like on BridgePlatformServiceModule.

    override suspend fun postComment(
        projectId: String,
        mrId: String,
        body: String
    ): String = poster.postComment(projectId, mrId, body)

    override suspend fun updateComment(
        projectId: String,
        mrId: String,
        commentId: String,
        body: String
    ) = poster.updateComment(projectId, mrId, commentId, body)

    override suspend fun findExistingComment(
        projectId: String,
        mrId: String,
        marker: String
    ): String? = poster.findExistingComment(projectId, mrId, marker)

    override suspend fun postInlineComment(
        projectId: String,
        mrId: String,
        body: String,
        filePath: String,
        line: Int,
        diffRefs: DiffRefs,
        startLine: Int?,
    ): String = poster.postInlineComment(projectId, mrId, body, filePath, line, diffRefs, startLine)
}
