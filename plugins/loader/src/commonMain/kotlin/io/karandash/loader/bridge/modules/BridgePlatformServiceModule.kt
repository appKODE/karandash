package io.karandash.loader.bridge.modules

import io.karandash.api.ModuleType
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.support.suspendCatching
import io.karandash.loader.bridge.BridgeConstants
import io.karandash.loader.bridge.BridgePart
import io.karandash.loader.bridge.BridgeTransport
import io.karandash.loader.bridge.guarded
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.models.support.KarandashJson

internal class BridgePlatformServiceModule(
    transport: BridgeTransport
) : BridgePart(transport),
    PlatformServiceModule {
    override val type: ModuleType get() = ModuleType.PLATFORM_SERVICE
    private val json = KarandashJson.Default

    private val provider by lazy { BridgeProviderModule(transport) }
    private val mutator by lazy { BridgeMutatorModule(transport) }

    override suspend fun postComment(
        projectId: String,
        mrId: String,
        body: String,
    ): String =
        transport.guarded("postComment", "") {
            transport.call("postComment", projectId, mrId, body) ?: ""
        }

    override suspend fun updateComment(
        projectId: String,
        mrId: String,
        commentId: String,
        body: String,
    ) {
        if (!transport.hasCapability("updateComment")) return
        transport.callVoid("updateComment", projectId, mrId, commentId, body)
    }

    override suspend fun findExistingComment(
        projectId: String,
        mrId: String,
        marker: String,
    ): String? {
        if (!transport.hasCapability("findExistingComment")) return null
        return transport
            .call("findExistingComment", projectId, mrId, marker)
            ?.takeIf { it != BridgeConstants.NULL_SENTINEL }
    }

    override suspend fun deleteComment(
        projectId: String,
        mrId: String,
        commentId: String,
    ) {
        if (!transport.hasCapability("deleteComment")) return
        transport.callVoid("deleteComment", projectId, mrId, commentId)
    }

    override suspend fun postInlineComment(
        projectId: String,
        mrId: String,
        body: String,
        filePath: String,
        line: Int,
        diffRefs: DiffRefs,
        startLine: Int?,
    ): String {
        if (!transport.hasCapability("postInlineComment")) return ""
        // ABI: (project_id, mr_id, body, file_path, #line, diff_refs_json, #start_line); start_line 0 = single line.
        val diffRefsJson = json.encodeToString(DiffRefs.serializer(), diffRefs)
        return transport.call(
            "postInlineComment",
            projectId,
            mrId,
            body,
            filePath,
            line.toString(),
            diffRefsJson,
            (startLine ?: 0).toString(),
        ) ?: ""
    }

    override suspend fun deleteMarkedInlineComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): Int {
        if (!transport.hasCapability("deleteMarkedInlineComments")) return 0
        return transport.call("deleteMarkedInlineComments", projectId, mrId, marker)?.toIntOrNull() ?: 0
    }

    override suspend fun resolveMarkedInlineComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): Int {
        if (!transport.hasCapability("resolveMarkedInlineComments")) return 0
        return transport.call("resolveMarkedInlineComments", projectId, mrId, marker)?.toIntOrNull() ?: 0
    }

    override suspend fun findAllMatchingComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): List<String> {
        if (!transport.hasCapability("findAllMatchingComments")) return emptyList()
        val result = transport.call("findAllMatchingComments", projectId, mrId, marker) ?: return emptyList()
        return suspendCatching { json.decodeFromString<List<String>>(result) }.getOrDefault(emptyList())
    }

    override suspend fun listOpenMergeRequestIds(
        projectId: String,
        targetBranch: String,
        limit: Int,
    ): List<String>? = optionalStrings("listOpenMergeRequestIds", projectId, targetBranch, limit.toString())

    override suspend fun listDismissedInlineComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): List<String>? = optionalStrings("listDismissedInlineComments", projectId, mrId, marker)

    /** A JSON string array from [op]; `null` when the guest lacks it, answers null or answers something else. */
    private suspend fun optionalStrings(
        op: String,
        vararg args: String,
    ): List<String>? {
        if (!transport.hasCapability(op)) return null
        val result = transport.call(op, *args) ?: return null
        return suspendCatching { json.decodeFromString<List<String>>(result) }.getOrNull()
    }

    override suspend fun renderSuggestionBlock(suggestedCode: String): String {
        if (!transport.hasCapability("renderSuggestionBlock")) {
            return buildString {
                appendLine("```diff")
                suggestedCode.lines().forEach { appendLine("+ $it") }
                append("```")
            }
        }
        return transport.call("renderSuggestionBlock", suggestedCode) ?: buildString {
            appendLine("```diff")
            suggestedCode.lines().forEach { appendLine("+ $it") }
            append("```")
        }
    }

    // The provider ops go through the guest like on BridgeProviderModule.

    override suspend fun fetchMergeRequest(
        projectId: String,
        mrId: String
    ): MergeRequest = provider.fetchMergeRequest(projectId, mrId)

    override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = provider.fetchMergeRequestByUrl(url)

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
}
