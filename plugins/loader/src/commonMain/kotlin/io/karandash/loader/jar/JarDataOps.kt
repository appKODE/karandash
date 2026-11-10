package io.karandash.loader.jar

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.context.ValidationContext
import io.karandash.api.finding.ReviewThread
import io.karandash.api.module.EnrichmentProviderModule
import io.karandash.api.module.FileGranularRuleModule
import io.karandash.api.module.FindingsAwareRuleModule
import io.karandash.api.module.ModuleUsage
import io.karandash.api.module.PipelineExtension
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.RuleModule
import io.karandash.api.platform.FetchOptions
import io.karandash.api.platform.MergeOptions
import io.karandash.api.platform.MergeRequestSearchHit
import io.karandash.api.platform.PlatformOperation
import io.karandash.api.platform.ProviderDetectionScore
import io.karandash.api.platform.RateLimitInfo
import io.karandash.api.platform.UnsupportedPlatformOperationException
import io.karandash.helpers.guest.GuestCodec.element
import io.karandash.helpers.guest.GuestCodec.encodeFindings
import io.karandash.helpers.guest.GuestCodec.encodeStrings
import io.karandash.helpers.guest.GuestCodec.findings
import io.karandash.helpers.guest.GuestCodec.json
import io.karandash.helpers.guest.GuestCodec.mr
import io.karandash.helpers.guest.GuestCodec.optionalElement
import io.karandash.helpers.guest.GuestCodec.strings
import io.karandash.loader.bridge.BridgeAbi
import io.karandash.loader.bridge.BridgeResultEnvelope
import io.karandash.logger.NoOpLogger
import io.karandash.models.ApprovalSignal
import io.karandash.models.BlameEntry
import io.karandash.models.FileChange
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * JAR bridge ops of the data-only SDK members: rule accounting and per-file / findings-aware validation, platform
 * queries, hints and `Result` ops (an `{ok|err}` envelope), enrichment `skipReason` and the pipeline-extension caches.
 * Plain-text answers travel raw; `null` is the op's "no answer" (the SDK default on the host). A query whose SDK default
 * throws [UnsupportedPlatformOperationException] answers it as the envelope's unsupported marker.
 */
@OptIn(ExperimentalKarandashApi::class)
internal object JarDataOps {
    private val rule: Map<String, JarBridgeOp> =
        mapOf(
            "validateFile" to jarOp<FileGranularRuleModule> {
                encodeFindings(validateFile(it[0], json.decodeFromString(FileChange.serializer(), it[1]), mr(it[2]), element(it[3])))
            },
            "validateWithFindings" to jarOp<FindingsAwareRuleModule> {
                val context = json.decodeFromString(ValidationContext.serializer(), it[3])
                encodeFindings(validateWithFindings(it[0], mr(it[1]), element(it[2]), context))
            },
            "usage" to jarOp<RuleModule> { usage?.let { json.encodeToString(ModuleUsage.serializer(), it) } },
            "requiredMergeRequestFields" to jarOp<RuleModule> { call ->
                encodeStrings(requiredMergeRequestFields(call[0], element(call[1])).map { it.name })
            },
            "tokensConsumed" to jarOp<RuleModule> { tokensConsumed()?.toString() },
            // the guest logs through its own context; nothing for the host to log
            "logCostSummary" to jarOp<RuleModule> {
                logCostSummary()
                ""
            },
            "skipReason" to jarOp<EnrichmentProviderModule> { skipReason() },
        )

    private val platformQueries: Map<String, JarBridgeOp> =
        mapOf(
            "checkAuth" to platformOp { checkAuth()?.toString() },
            "fetchFileBlame" to platformOp {
                json.encodeToString(ListSerializer(BlameEntry.serializer()), fetchFileBlame(it[0], it[1], it[2]))
            },
            "fetchFileContent" to unsupportedOp { fetchFileContent(it[0], it[1], it[2]) },
            "supportsFileContent" to platformOp { supportsFileContent.toString() },
            "searchMergeRequests" to unsupportedOp { call ->
                val query = element(call[1]) as? JsonObject
                val text = (query?.get("text") as? JsonPrimitive)?.contentOrNull
                val author = (query?.get("author") as? JsonPrimitive)?.contentOrNull
                searchMergeRequests(call[0], text, author, BridgeAbi.intArg(call[2]))
                    ?.let { json.encodeToString(ListSerializer(MergeRequestSearchHit.serializer()), it) }
            },
            "isProjectMember" to platformOp { isProjectMember(it[0], it[1])?.toString() },
            "currentUsername" to platformOp { currentUsername() },
            "fetchApprovalSignals" to unsupportedOp {
                json.encodeToString(ListSerializer(ApprovalSignal.serializer()), fetchApprovalSignals(it[0], it[1]))
            },
            "rateLimitStatus" to platformOp { rateLimitStatus()?.let { json.encodeToString(RateLimitInfo.serializer(), it) } },
            "permissionScope" to platformOp { permissionScope() },
            "tokenLocationHint" to platformOp { tokenLocationHint() },
            "notFoundGuidance" to platformOp { notFoundGuidance() },
            "unavailableMergeRequestFields" to platformOp { encodeStrings(unavailableMergeRequestFields().map { it.name }) },
            "detectConfidence" to platformOp { call ->
                val sent = element(call[0]) as? JsonObject
                detectConfidence { name -> (sent?.get(name) as? JsonPrimitive)?.contentOrNull }
                    ?.let { json.encodeToString(ProviderDetectionScore.serializer(), it) }
            },
            "supports" to platformOp { call ->
                PlatformOperation.entries
                    .find { it.name == call[0] }
                    ?.let { supports(it) }
                    ?.toString() ?: false.toString()
            },
            "resolveMarkedInlineCommentsForFiles" to platformOp {
                resolveMarkedInlineCommentsForFiles(it[0], it[1], it[2], strings(it[3]).toSet()).toString()
            },
            "listReviewThreads" to unsupportedOp {
                listReviewThreads(it[0], it[1])?.let { threads -> json.encodeToString(ListSerializer(ReviewThread.serializer()), threads) }
            },
        )

    private val platformResults: Map<String, JarBridgeOp> =
        mapOf(
            "addReviewers" to resultOp { addReviewers(it[0], it[1], strings(it[2])) },
            "removeReviewers" to resultOp { removeReviewers(it[0], it[1], strings(it[2])) },
            "mergeMr" to resultOp { mergeMr(it[0], it[1], json.decodeFromString(MergeOptions.serializer(), it[2])) },
            "closeMr" to resultOp { closeMr(it[0], it[1]) },
            "reopenMr" to resultOp { reopenMr(it[0], it[1]) },
            "resolveThread" to resultOp { resolveThread(it[0], it[1], it[2], it[3].ifEmpty { null }) },
            "replyToThread" to resultOp { replyToThread(it[0], it[1], it[2], it[3]) },
            "updateNote" to resultOp { updateNote(it[0], it[1], it[2], it[3], it[4]) },
        )

    private val pipeline: Map<String, JarBridgeOp> =
        mapOf(
            "computeRuleModuleConfig" to pipelineOp { (computeRuleModuleConfig(optionalElement(it[0])) ?: JsonNull).toString() },
            "costSummary" to pipelineOp { costSummary() },
            "fetchOptions" to
                pipelineOp { fetchOptions(optionalElement(it[0]))?.let { json.encodeToString(FetchOptions.serializer(), it) } },
            "excludedRules" to pipelineOp { encodeStrings(excludedRules(optionalElement(it[0]), strings(it[1]).toSet())) },
            // the guest logs through its own context, so the SDK's logger argument is a no-op here
            "cachedFindings" to pipelineOp { getCachedFindings(mr(it[0]), optionalElement(it[1]), NoOpLogger)?.let(::encodeFindings) },
            "saveIncrementalState" to pipelineOp {
                saveIncrementalState(mr(it[0]), findings(it[1]))
                null
            },
            "incrementalChangedFiles" to pipelineOp { incrementalChangedFiles()?.let(::encodeStrings) },
            "incrementalCachedFindings" to pipelineOp { incrementalCachedFindings()?.let(::encodeFindings) },
        )

    val OPS: Map<String, JarBridgeOp> = rule + platformQueries + platformResults + pipeline

    private fun platformOp(run: suspend PlatformServiceModule.(JarInvocation) -> String?): JarBridgeOp = jarOp(run)

    /** [platformOp] for a query whose SDK default throws: the exception becomes the unsupported marker, not a failure. */
    private fun unsupportedOp(run: suspend PlatformServiceModule.(JarInvocation) -> String?): JarBridgeOp =
        platformOp { call ->
            try {
                run(call)
            } catch (e: UnsupportedPlatformOperationException) {
                BridgeResultEnvelope.encode(Result.failure(e))
            }
        }

    private fun pipelineOp(run: suspend PipelineExtension.(JarInvocation) -> String?): JarBridgeOp = jarOp(run)

    private fun resultOp(run: suspend PlatformServiceModule.(JarInvocation) -> Result<Unit>): JarBridgeOp =
        jarOp<PlatformServiceModule> { BridgeResultEnvelope.encode(run(it)) }
}
