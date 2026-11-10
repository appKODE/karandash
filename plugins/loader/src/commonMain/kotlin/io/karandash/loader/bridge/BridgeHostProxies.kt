package io.karandash.loader.bridge

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.Module
import io.karandash.api.ModuleDescriptor
import io.karandash.api.ModuleType
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.ModuleMessage
import io.karandash.api.finding.ValidationReport
import io.karandash.api.module.FormatModule
import io.karandash.api.module.OutputModule
import io.karandash.api.module.PipelineEnvironment
import io.karandash.api.module.PipelineExtension
import io.karandash.api.platform.FetchOptions
import io.karandash.api.support.suspendCatching
import io.karandash.logger.Logger
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Host-side proxies for the guest module types whose SDK interface the god bridges do not implement
 * (FORMAT, OUTPUT, PIPELINE_EXTENSION). They call the guest through the [BridgeAbi] ops directly and
 * read their flags from the guest's `describe()` result, so JS, WASM, native and JAR guests share them.
 */

private val json = KarandashJson.Default
private val findingsSerializer = ListSerializer(Finding.serializer())
private val stringList = ListSerializer(String.serializer())

/**
 * [FormatModule] proxy. The guest returns a [FormattedReport] JSON object, or a plain string taken as
 * the report body.
 */
internal class BridgeFormatProxy(
    guest: Module,
    private val transport: BridgeTransport,
    descriptor: BridgeDescriptor,
) : FormatModule,
    Module by guest {
    override val type: ModuleType get() = ModuleType.FORMAT
    override val formatId: String = transport.getProperty(OP_FORMAT_ID).ifEmpty { guest.id }
    override val configKey: String get() = formatId
    override val isDefaultInteractive: Boolean = descriptor.bool(ModuleDescriptor.IS_DEFAULT_INTERACTIVE) ?: false
    override val isDefaultPlatform: Boolean = descriptor.bool(ModuleDescriptor.IS_DEFAULT_PLATFORM) ?: false

    override suspend fun format(
        report: ValidationReport,
        config: JsonElement,
    ): FormattedReport {
        val result =
            transport.call(OP_FORMAT, KarandashJson.Report.encodeToString(ValidationReport.serializer(), report), config.toString())
                ?: error("format '$formatId' returned no result")
        return if (result.trimStart().startsWith("{")) {
            json.decodeFromString(FormattedReport.serializer(), result)
        } else {
            FormattedReport(formatId = formatId, body = result)
        }
    }

    private companion object {
        const val OP_FORMAT_ID = "formatId"
        const val OP_FORMAT = "format"
    }
}

/** [OutputModule] proxy. The guest's `output` returns null on success, else an error message (thrown here). */
@OptIn(ExperimentalKarandashApi::class)
internal class BridgeOutputProxy(
    guest: Module,
    private val transport: BridgeTransport,
    descriptor: BridgeDescriptor,
) : OutputModule,
    Module by guest {
    override val type: ModuleType get() = ModuleType.OUTPUT
    override val outputId: String = transport.getProperty(OP_OUTPUT_ID).ifEmpty { guest.id }
    override val configKey: String get() = outputId
    override val acceptedFormats: Set<String> = descriptor.stringList(ModuleDescriptor.ACCEPTED_FORMATS)?.toSet() ?: emptySet()
    override val requiresPlatformContext: Boolean = descriptor.bool(ModuleDescriptor.REQUIRES_PLATFORM_CONTEXT) ?: false
    override val supportsFileOutput: Boolean = descriptor.bool(ModuleDescriptor.SUPPORTS_FILE_OUTPUT) ?: false
    override val preferredFormat: String? = descriptor.string(ModuleDescriptor.PREFERRED_FORMAT)
    override val hasExternalSideEffects: Boolean = descriptor.bool(ModuleDescriptor.HAS_EXTERNAL_SIDE_EFFECTS) ?: true
    override val isDefaultInteractive: Boolean = descriptor.bool(ModuleDescriptor.IS_DEFAULT_INTERACTIVE) ?: false
    override val isDefaultPlatform: Boolean = descriptor.bool(ModuleDescriptor.IS_DEFAULT_PLATFORM) ?: false

    override suspend fun output(
        report: FormattedReport,
        config: JsonElement,
    ) {
        val failure = transport.call(OP_OUTPUT, json.encodeToString(FormattedReport.serializer(), report), config.toString())
        check(failure.isNullOrEmpty() || failure == BridgeConstants.NULL_SENTINEL) { "output '$outputId' failed: $failure" }
    }

    private companion object {
        const val OP_OUTPUT_ID = "outputId"
        const val OP_OUTPUT = "output"
    }
}

/**
 * [PipelineExtension] proxy for the hooks a guest can serve over the ABI. A hook the guest does not
 * export keeps the SDK default; a failing or malformed findings hook leaves the findings unchanged.
 */
@OptIn(ExperimentalKarandashApi::class)
internal class BridgePipelineExtensionProxy(
    guest: Module,
    private val transport: BridgeTransport,
    descriptor: BridgeDescriptor,
) : PipelineExtension,
    Module by guest {
    override val type: ModuleType get() = ModuleType.PIPELINE_EXTENSION

    private val declaresBlame: Boolean = descriptor.bool(ModuleDescriptor.NEEDS_BLAME) ?: false

    override fun needsBlame(config: JsonElement?): Boolean = declaresBlame

    private val declaresHeadContent: Boolean = descriptor.bool(ModuleDescriptor.NEEDS_HEAD_CONTENT) ?: false

    override fun needsHeadContent(config: JsonElement?): Boolean = declaresHeadContent

    override suspend fun onConfigured(env: PipelineEnvironment) {
        if (!transport.hasCapability(OP_ON_CONFIGURED)) return
        val envJson = buildJsonObject {
            put("rawConfig", env.rawConfig ?: JsonNull)
            put("statsDir", env.statsDir)
            put("platformId", env.platformId)
            put("aiServiceAvailable", env.aiService?.isAvailable == true)
        }
        transport.callVoid(OP_ON_CONFIGURED, envJson.toString())
    }

    override suspend fun onPostFetch(
        mr: MergeRequest,
        config: JsonElement?,
    ) {
        if (!transport.hasCapability(OP_ON_POST_FETCH)) return
        transport.callVoid(OP_ON_POST_FETCH, mr.toJson(), (config ?: JsonNull).toString())
    }

    override suspend fun afterValidation(
        findings: List<Finding>,
        mr: MergeRequest,
        config: JsonElement?,
    ): List<Finding> = findingsHook(OP_AFTER_VALIDATION, findings, mr.toJson(), (config ?: JsonNull).toString())

    override suspend fun beforeReporting(
        findings: List<Finding>,
        mr: MergeRequest,
    ): List<Finding> = findingsHook(OP_BEFORE_REPORTING, findings, mr.toJson())

    override suspend fun onPipelineComplete() {
        if (transport.hasCapability(OP_ON_PIPELINE_COMPLETE)) transport.callVoid(OP_ON_PIPELINE_COMPLETE)
    }

    override suspend fun invalidateRules(ruleIds: Set<String>) {
        if (!transport.hasCapability(OP_INVALIDATE_RULES)) return
        transport.callVoid(OP_INVALIDATE_RULES, json.encodeToString(ListSerializer(String.serializer()), ruleIds.toList()))
    }

    override fun computeRuleModuleConfig(configElement: JsonElement?): JsonElement? =
        transport.querySync(OP_COMPUTE_RULE_MODULE_CONFIG, JsonElement.serializer(), configElement.orNull())?.takeUnless { it is JsonNull }

    /** Plain text; blank reads as none. */
    override fun costSummary(): String? {
        if (!transport.hasCapability(OP_COST_SUMMARY)) return null
        return transport
            .guarded(OP_COST_SUMMARY, null) { transport.callSync(OP_COST_SUMMARY) }
            ?.takeUnless { it.isBlank() || it == BridgeConstants.NULL_SENTINEL }
    }

    override fun fetchOptions(config: JsonElement?): FetchOptions? =
        transport.querySync(OP_FETCH_OPTIONS, FetchOptions.serializer(), config.orNull())

    override fun excludedRules(
        config: JsonElement?,
        ruleIds: Set<String>,
    ): Set<String> =
        transport
            .querySync(OP_EXCLUDED_RULES, stringList, config.orNull(), json.encodeToString(stringList, ruleIds.toList()))
            ?.toSet()
            .orEmpty()

    /** The guest logs through its own context, so [logger] is not sent. */
    override fun getCachedFindings(
        mr: MergeRequest,
        config: JsonElement?,
        logger: Logger,
    ): List<Finding>? = transport.querySync(OP_CACHED_FINDINGS, findingsSerializer, mr.toJson(), config.orNull())

    override fun saveIncrementalState(
        mr: MergeRequest,
        findings: List<Finding>,
    ) {
        if (!transport.hasCapability(OP_SAVE_INCREMENTAL_STATE)) return
        transport.guarded(OP_SAVE_INCREMENTAL_STATE, null) {
            transport.callSync(OP_SAVE_INCREMENTAL_STATE, mr.toJson(), json.encodeToString(findingsSerializer, findings))
        }
    }

    override fun incrementalChangedFiles(): Set<String>? = transport.querySync(OP_INCREMENTAL_CHANGED_FILES, stringList)?.toSet()

    override fun incrementalCachedFindings(): List<Finding>? = transport.querySync(OP_INCREMENTAL_CACHED_FINDINGS, findingsSerializer)

    private fun JsonElement?.orNull(): String = (this ?: JsonNull).toString()

    private suspend fun findingsHook(
        op: String,
        findings: List<Finding>,
        mrJson: String,
        configJson: String? = null,
    ): List<Finding> {
        if (!transport.hasCapability(op)) return findings
        val findingsJson = json.encodeToString(findingsSerializer, findings)
        return transport.guarded(op, findings) {
            val result = if (configJson == null) {
                transport.call(op, findingsJson, mrJson)
            } else {
                transport.call(op, findingsJson, mrJson, configJson)
            }
            result?.let { json.decodeFromString(findingsSerializer, it) } ?: findings
        }
    }

    private fun MergeRequest.toJson(): String = json.encodeToString(MergeRequest.serializer(), this)

    private companion object {
        const val OP_ON_CONFIGURED = "onConfigured"
        const val OP_ON_POST_FETCH = "onPostFetch"
        const val OP_AFTER_VALIDATION = "afterValidation"
        const val OP_BEFORE_REPORTING = "beforeReporting"
        const val OP_ON_PIPELINE_COMPLETE = "onPipelineComplete"
        const val OP_INVALIDATE_RULES = "invalidateRules"
        const val OP_COMPUTE_RULE_MODULE_CONFIG = "computeRuleModuleConfig"
        const val OP_COST_SUMMARY = "costSummary"
        const val OP_FETCH_OPTIONS = "fetchOptions"
        const val OP_EXCLUDED_RULES = "excludedRules"
        const val OP_CACHED_FINDINGS = "cachedFindings"
        const val OP_SAVE_INCREMENTAL_STATE = "saveIncrementalState"
        const val OP_INCREMENTAL_CHANGED_FILES = "incrementalChangedFiles"
        const val OP_INCREMENTAL_CACHED_FINDINGS = "incrementalCachedFindings"
    }
}

/** Reads the guest's `messages` export (a JSON array of [ModuleMessage]); absent or malformed = none. */
internal suspend fun BridgeTransport.readMessages(): List<ModuleMessage> {
    if (!hasCapability(OP_MESSAGES)) return emptyList()
    val result = call(OP_MESSAGES) ?: return emptyList()
    return suspendCatching { json.decodeFromString(ListSerializer(ModuleMessage.serializer()), result) }
        .getOrDefault(emptyList())
}

private const val OP_MESSAGES = "messages"
