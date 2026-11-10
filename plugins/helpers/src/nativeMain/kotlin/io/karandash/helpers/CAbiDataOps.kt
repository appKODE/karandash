@file:OptIn(ExperimentalForeignApi::class)

package io.karandash.helpers

import io.karandash.api.finding.ReviewThread
import io.karandash.api.module.ModuleUsage
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.RuleModule
import io.karandash.api.platform.MergeOptions
import io.karandash.api.platform.MergeRequestSearchHit
import io.karandash.api.platform.PlatformOperation
import io.karandash.api.platform.ProviderDetectionScore
import io.karandash.api.platform.RateLimitInfo
import io.karandash.api.platform.UnsupportedPlatformOperationException
import io.karandash.helpers.CAbiBridge.toCString
import io.karandash.helpers.CAbiBridge.toKStringOrEmpty
import io.karandash.models.ApprovalSignal
import io.karandash.models.BlameEntry
import io.karandash.models.support.KarandashJson
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * C ABI wrappers of the data-only SDK members for generated Kotlin/Native guests, same wire as every other
 * transport: JSON in and out, plain text for text answers, NULL for "no answer" (the host's SDK default), and the
 * `{ok|err}` envelope for a platform `Result` op and for an unsupported query whose SDK default throws. `validateFile` / `validateWithFindings` are not generated:
 * exporting them makes the host treat the rule as file-granular / findings-aware, so a plugin that implements those
 * interfaces writes the two exports by hand.
 */
object CAbiDataOps {
    private val json = KarandashJson.Default

    fun usage(module: RuleModule): CPointer<ByteVar>? = encoded(ModuleUsage.serializer()) { module.usage }

    fun requiredMergeRequestFields(
        module: RuleModule,
        ruleId: CPointer<ByteVar>?,
        configJson: CPointer<ByteVar>?,
    ): CPointer<ByteVar>? =
        encoded(ListSerializer(String.serializer())) {
            module.requiredMergeRequestFields(ruleId.str(), element(configJson) ?: JsonNull).map { it.name }
        }

    fun tokensConsumed(module: RuleModule): CPointer<ByteVar>? = text { module.tokensConsumed()?.toString() }

    /** The guest logs through its own context; the host gets an empty line. */
    fun logCostSummary(module: RuleModule): CPointer<ByteVar>? =
        text {
            module.logCostSummary()
            ""
        }

    fun checkAuth(module: PlatformServiceModule): CPointer<ByteVar>? = text { module.checkAuth()?.toString() }

    fun fetchFileBlame(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        filePath: CPointer<ByteVar>?,
        ref: CPointer<ByteVar>?,
    ): CPointer<ByteVar>? =
        encoded(ListSerializer(BlameEntry.serializer())) {
            module.fetchFileBlame(projectId.str(), filePath.str(), ref.str())
        }

    fun fetchFileContent(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        ref: CPointer<ByteVar>?,
        path: CPointer<ByteVar>?,
    ): CPointer<ByteVar>? = query { module.fetchFileContent(projectId.str(), ref.str(), path.str()) }

    fun supportsFileContent(module: PlatformServiceModule): Int = flag { module.supportsFileContent }

    fun searchMergeRequests(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        queryJson: CPointer<ByteVar>?,
        limit: Int,
    ): CPointer<ByteVar>? =
        query {
            val query = element(queryJson) as? JsonObject
            module
                .searchMergeRequests(projectId.str(), query.string("text"), query.string("author"), limit)
                ?.let { json.encodeToString(ListSerializer(MergeRequestSearchHit.serializer()), it) }
        }

    fun isProjectMember(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        username: CPointer<ByteVar>?,
    ): CPointer<ByteVar>? = text { module.isProjectMember(projectId.str(), username.str())?.toString() }

    fun currentUsername(module: PlatformServiceModule): CPointer<ByteVar>? = text { module.currentUsername() }

    fun fetchApprovalSignals(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        mrId: CPointer<ByteVar>?,
    ): CPointer<ByteVar>? =
        query { json.encodeToString(ListSerializer(ApprovalSignal.serializer()), module.fetchApprovalSignals(projectId.str(), mrId.str())) }

    fun rateLimitStatus(module: PlatformServiceModule): CPointer<ByteVar>? =
        encoded(RateLimitInfo.serializer()) { module.rateLimitStatus() }

    fun permissionScope(module: PlatformServiceModule): CPointer<ByteVar>? = text { module.permissionScope() }

    fun tokenLocationHint(module: PlatformServiceModule): CPointer<ByteVar>? = text { module.tokenLocationHint() }

    fun notFoundGuidance(module: PlatformServiceModule): CPointer<ByteVar>? = text { module.notFoundGuidance() }

    fun unavailableMergeRequestFields(module: PlatformServiceModule): CPointer<ByteVar>? =
        encoded(ListSerializer(String.serializer())) { module.unavailableMergeRequestFields().map { it.name } }

    fun detectConfidence(
        module: PlatformServiceModule,
        envJson: CPointer<ByteVar>?,
    ): CPointer<ByteVar>? =
        encoded(ProviderDetectionScore.serializer()) {
            val sent = element(envJson) as? JsonObject
            module.detectConfidence { name -> sent.string(name) }
        }

    fun supports(
        module: PlatformServiceModule,
        operation: CPointer<ByteVar>?,
    ): Int = flag { PlatformOperation.entries.find { it.name == operation.str() }?.let { module.supports(it) } == true }

    fun resolveMarkedInlineCommentsForFiles(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        mrId: CPointer<ByteVar>?,
        marker: CPointer<ByteVar>?,
        filePathsJson: CPointer<ByteVar>?,
    ): Int =
        CAbiBridge.call(0) {
            val paths = json.decodeFromString(ListSerializer(String.serializer()), filePathsJson.str()).toSet()
            module.resolveMarkedInlineCommentsForFiles(projectId.str(), mrId.str(), marker.str(), paths)
        }

    fun listReviewThreads(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        mrId: CPointer<ByteVar>?,
    ): CPointer<ByteVar>? =
        query {
            module
                .listReviewThreads(
                    projectId.str(),
                    mrId.str()
                )?.let { json.encodeToString(ListSerializer(ReviewThread.serializer()), it) }
        }

    fun addReviewers(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        mrId: CPointer<ByteVar>?,
        usernamesJson: CPointer<ByteVar>?,
    ): CPointer<ByteVar> = result { module.addReviewers(projectId.str(), mrId.str(), strings(usernamesJson)) }

    fun removeReviewers(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        mrId: CPointer<ByteVar>?,
        usernamesJson: CPointer<ByteVar>?,
    ): CPointer<ByteVar> = result { module.removeReviewers(projectId.str(), mrId.str(), strings(usernamesJson)) }

    fun mergeMr(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        mrId: CPointer<ByteVar>?,
        optionsJson: CPointer<ByteVar>?,
    ): CPointer<ByteVar> =
        result { module.mergeMr(projectId.str(), mrId.str(), json.decodeFromString(MergeOptions.serializer(), optionsJson.str())) }

    fun closeMr(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        mrId: CPointer<ByteVar>?,
    ): CPointer<ByteVar> = result { module.closeMr(projectId.str(), mrId.str()) }

    fun reopenMr(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        mrId: CPointer<ByteVar>?,
    ): CPointer<ByteVar> = result { module.reopenMr(projectId.str(), mrId.str()) }

    fun resolveThread(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        mrId: CPointer<ByteVar>?,
        threadId: CPointer<ByteVar>?,
        note: CPointer<ByteVar>?,
    ): CPointer<ByteVar> = result { module.resolveThread(projectId.str(), mrId.str(), threadId.str(), note.str().ifEmpty { null }) }

    fun replyToThread(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        mrId: CPointer<ByteVar>?,
        threadId: CPointer<ByteVar>?,
        body: CPointer<ByteVar>?,
    ): CPointer<ByteVar> = result { module.replyToThread(projectId.str(), mrId.str(), threadId.str(), body.str()) }

    fun updateNote(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        mrId: CPointer<ByteVar>?,
        threadId: CPointer<ByteVar>?,
        noteId: CPointer<ByteVar>?,
        body: CPointer<ByteVar>?,
    ): CPointer<ByteVar> = result { module.updateNote(projectId.str(), mrId.str(), threadId.str(), noteId.str(), body.str()) }

    /** The `{ok|err}` envelope of a `Result<Unit>` op; `unsupported` marks [UnsupportedPlatformOperationException]. */
    internal fun envelope(result: Result<Unit>): String {
        val error = result.exceptionOrNull() ?: return """{"ok":true}"""
        return buildJsonObject {
            put("err", error.message ?: error::class.simpleName.orEmpty())
            if (error is UnsupportedPlatformOperationException) put("unsupported", true)
        }.toString()
    }

    private fun result(block: suspend () -> Result<Unit>): CPointer<ByteVar> =
        toCString(CAbiBridge.call(envelope(Result.failure(IllegalStateException("plugin call failed")))) { envelope(block()) })

    private fun text(block: suspend () -> String?): CPointer<ByteVar>? = CAbiBridge.call(null) { block()?.let(::toCString) }

    /** [text] for a query whose SDK default throws: [UnsupportedPlatformOperationException] answers the unsupported marker. */
    internal fun query(block: suspend () -> String?): CPointer<ByteVar>? =
        text {
            try {
                block()
            } catch (e: UnsupportedPlatformOperationException) {
                envelope(Result.failure(e))
            }
        }

    private fun <T : Any> encoded(
        serializer: KSerializer<T>,
        block: suspend () -> T?,
    ): CPointer<ByteVar>? = text { block()?.let { json.encodeToString(serializer, it) } }

    private fun flag(block: suspend () -> Boolean): Int = CAbiBridge.call(0) { if (block()) 1 else 0 }

    private fun strings(value: CPointer<ByteVar>?): List<String> = json.decodeFromString(ListSerializer(String.serializer()), value.str())

    private fun element(value: CPointer<ByteVar>?) = value.str().let { if (it.isBlank()) null else json.parseToJsonElement(it) }

    private fun JsonObject?.string(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull

    private fun CPointer<ByteVar>?.str(): String = toKStringOrEmpty()
}
