package io.karandash.loader.bridge

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.ModuleType
import io.karandash.api.finding.ReviewThread
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.MergeOptions
import io.karandash.api.platform.MergeRequestField
import io.karandash.api.platform.MergeRequestSearchHit
import io.karandash.api.platform.PlatformOperation
import io.karandash.api.platform.ProviderDetectionScore
import io.karandash.api.platform.RateLimitInfo
import io.karandash.models.ApprovalSignal
import io.karandash.models.BlameEntry
import io.karandash.models.support.KarandashJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val json = KarandashJson.Default
private val stringList = ListSerializer(String.serializer())

/**
 * [PlatformServiceModule] proxy for the members the god bridge leaves at the SDK default: `supports`, the queries and
 * hints (a missing export or a NULL answer is the SDK default; for the queries whose default throws, a missing export
 * or the guest's unsupported marker is [io.karandash.api.platform.UnsupportedPlatformOperationException]) and the `Result` ops (an `{ok|err}` envelope; a missing
 * export is [io.karandash.api.platform.UnsupportedPlatformOperationException], warned once per op).
 */
@OptIn(ExperimentalKarandashApi::class)
internal class BridgePlatformServiceProxy(
    private val guest: PlatformServiceModule,
    private val transport: BridgeTransport,
) : PlatformServiceModule by guest {
    override val type: ModuleType get() = ModuleType.PLATFORM_SERVICE

    private val warnedMissing = MutableStateFlow<Set<String>>(emptySet())

    /** The guest's own `supports`, else false (the SDK default). */
    override suspend fun supports(operation: PlatformOperation): Boolean =
        transport.query("supports", Boolean.serializer(), operation.name) ?: false

    override suspend fun checkAuth(): Boolean? = transport.query("checkAuth", Boolean.serializer())

    override suspend fun rateLimitStatus(): RateLimitInfo? = transport.query("rateLimitStatus", RateLimitInfo.serializer())

    override suspend fun currentUsername(): String? = text("currentUsername")

    override suspend fun isProjectMember(
        projectId: String,
        username: String,
    ): Boolean? = transport.query("isProjectMember", Boolean.serializer(), projectId, username)

    override suspend fun fetchFileBlame(
        projectId: String,
        filePath: String,
        ref: String,
    ): List<BlameEntry> = transport.query("fetchFileBlame", ListSerializer(BlameEntry.serializer()), projectId, filePath, ref).orEmpty()

    override val supportsFileContent: Boolean get() = transport.getBoolProperty("supportsFileContent", false)

    override suspend fun fetchFileContent(
        projectId: String,
        ref: String,
        path: String,
    ): String? = transport.unsupportedQuery("fetchFileContent", { it }, projectId, ref, path)

    override suspend fun fetchApprovalSignals(
        projectId: String,
        mrId: String,
    ): List<ApprovalSignal> =
        transport.unsupportedQuery("fetchApprovalSignals", decoder(ListSerializer(ApprovalSignal.serializer())), projectId, mrId).orEmpty()

    override suspend fun searchMergeRequests(
        projectId: String,
        text: String?,
        author: String?,
        limit: Int,
    ): List<MergeRequestSearchHit>? {
        val query = buildJsonObject {
            put("text", text)
            put("author", author)
        }
        return transport.unsupportedQuery(
            "searchMergeRequests",
            decoder(ListSerializer(MergeRequestSearchHit.serializer())),
            projectId,
            query.toString(),
            limit.toString(),
        )
    }

    /** `env_json` holds the values of the guest's own `supportedEnvVars`, as for `detectCiEnvironment`. */
    override suspend fun detectConfidence(env: (String) -> String?): ProviderDetectionScore? {
        if (!transport.hasCapability("detectConfidence")) return guest.detectConfidence(env)
        val names = transport.guarded("supportedEnvVars", emptyList()) {
            json.decodeFromString(stringList, transport.getProperty("supportedEnvVars").ifEmpty { "[]" })
        }
        val envJson = buildJsonObject { names.forEach { name -> env(name)?.let { put(name, it) } } }
        return transport.query("detectConfidence", ProviderDetectionScore.serializer(), envJson.toString())
    }

    override fun permissionScope(): String? = textSync("permissionScope")

    override fun tokenLocationHint(): String? = textSync("tokenLocationHint")

    override fun notFoundGuidance(): String? = textSync("notFoundGuidance")

    /** Field names this host does not know (a newer SDK's) are dropped. */
    override fun unavailableMergeRequestFields(): Set<MergeRequestField> =
        transport
            .querySync("unavailableMergeRequestFields", stringList)
            .orEmpty()
            .mapNotNullTo(mutableSetOf()) { name -> MergeRequestField.entries.find { it.name == name } }

    override suspend fun resolveMarkedInlineCommentsForFiles(
        projectId: String,
        mrId: String,
        marker: String,
        filePaths: Set<String>,
    ): Int {
        if (!transport.hasCapability("resolveMarkedInlineCommentsForFiles")) {
            return guest.resolveMarkedInlineCommentsForFiles(projectId, mrId, marker, filePaths)
        }
        val paths = json.encodeToString(stringList, filePaths.toList())
        return transport.query("resolveMarkedInlineCommentsForFiles", Int.serializer(), projectId, mrId, marker, paths) ?: 0
    }

    override suspend fun listReviewThreads(
        projectId: String,
        mrId: String,
    ): List<ReviewThread>? =
        transport.unsupportedQuery("listReviewThreads", decoder(ListSerializer(ReviewThread.serializer())), projectId, mrId)

    override suspend fun addReviewers(
        projectId: String,
        mrId: String,
        usernames: List<String>,
    ): Result<Unit> = result("addReviewers", projectId, mrId, json.encodeToString(stringList, usernames))

    override suspend fun removeReviewers(
        projectId: String,
        mrId: String,
        usernames: List<String>,
    ): Result<Unit> = result("removeReviewers", projectId, mrId, json.encodeToString(stringList, usernames))

    override suspend fun mergeMr(
        projectId: String,
        mrId: String,
        options: MergeOptions,
    ): Result<Unit> = result("mergeMr", projectId, mrId, json.encodeToString(MergeOptions.serializer(), options))

    override suspend fun closeMr(
        projectId: String,
        mrId: String,
    ): Result<Unit> = result("closeMr", projectId, mrId)

    override suspend fun reopenMr(
        projectId: String,
        mrId: String,
    ): Result<Unit> = result("reopenMr", projectId, mrId)

    /** A null [note] travels as `""`. */
    override suspend fun resolveThread(
        projectId: String,
        mrId: String,
        threadId: String,
        note: String?,
    ): Result<Unit> = result("resolveThread", projectId, mrId, threadId, note.orEmpty())

    override suspend fun replyToThread(
        projectId: String,
        mrId: String,
        threadId: String,
        body: String,
    ): Result<Unit> = result("replyToThread", projectId, mrId, threadId, body)

    override suspend fun updateNote(
        projectId: String,
        mrId: String,
        threadId: String,
        noteId: String,
        body: String,
    ): Result<Unit> = result("updateNote", projectId, mrId, threadId, noteId, body)

    private suspend fun result(
        op: String,
        vararg args: String,
    ): Result<Unit> = transport.resultCall(op, warnedMissing, *args)

    private fun <T> decoder(serializer: DeserializationStrategy<T>): (String) -> T = { json.decodeFromString(serializer, it) }

    /** A plain-text answer; no export, NULL or a failing call is null. */
    private suspend fun text(
        op: String,
        vararg args: String,
    ): String? {
        if (!transport.hasCapability(op)) return null
        return transport.guarded(op, null) { transport.call(op, *args) }?.takeUnless { it == BridgeConstants.NULL_SENTINEL }
    }

    private fun textSync(op: String): String? {
        if (!transport.hasCapability(op)) return null
        return transport.guarded(op, null) { transport.callSync(op) }?.takeUnless { it.isEmpty() || it == BridgeConstants.NULL_SENTINEL }
    }
}
