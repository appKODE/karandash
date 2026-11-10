package io.karandash.loader.bridge.modules

import io.karandash.api.ModuleType
import io.karandash.api.module.PlatformServiceModule
import io.karandash.loader.bridge.BridgePart
import io.karandash.loader.bridge.BridgeTransport
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

internal class BridgeMutatorModule(
    transport: BridgeTransport
) : BridgePart(transport),
    PlatformServiceModule {
    override val type: ModuleType get() = ModuleType.PLATFORM_SERVICE
    private val provider by lazy { BridgeProviderModule(transport) }
    private val poster by lazy { BridgePlatformServiceModule(transport) }

    override suspend fun addLabels(
        projectId: String,
        mrId: String,
        labels: List<String>,
    ) {
        if (!transport.hasCapability("addLabels")) return
        transport.callVoid("addLabels", projectId, mrId, labelsJson(labels))
    }

    override suspend fun removeLabels(
        projectId: String,
        mrId: String,
        labels: List<String>,
    ) {
        if (!transport.hasCapability("removeLabels")) return
        transport.callVoid("removeLabels", projectId, mrId, labelsJson(labels))
    }

    override suspend fun setDraft(
        projectId: String,
        mrId: String,
        draft: Boolean,
    ) {
        if (!transport.hasCapability("setDraft")) return
        transport.callVoid("setDraft", projectId, mrId, draft.toString())
    }

    override suspend fun updateTitle(
        projectId: String,
        mrId: String,
        title: String,
    ) {
        if (!transport.hasCapability("updateTitle")) return
        transport.callVoid("updateTitle", projectId, mrId, title)
    }

    override suspend fun updateDescription(
        projectId: String,
        mrId: String,
        description: String,
    ) {
        if (!transport.hasCapability("updateDescription")) return
        transport.callVoid("updateDescription", projectId, mrId, description)
    }

    override suspend fun approve(
        projectId: String,
        mrId: String
    ) {
        if (!transport.hasCapability("approve")) return
        transport.callVoid("approve", projectId, mrId)
    }

    override suspend fun unapprove(
        projectId: String,
        mrId: String
    ) {
        if (!transport.hasCapability("unapprove")) return
        transport.callVoid("unapprove", projectId, mrId)
    }

    // The provider ops go through the guest like on BridgeProviderModule.

    override suspend fun fetchMergeRequest(
        projectId: String,
        mrId: String
    ): MergeRequest = provider.fetchMergeRequest(projectId, mrId)

    override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = provider.fetchMergeRequestByUrl(url)

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

    /** Labels cross every bridge as one JSON list (`["a","b"]`), so a label may contain a comma. */
    private fun labelsJson(labels: List<String>): String = JsonArray(labels.map(::JsonPrimitive)).toString()
}
