package io.karandash.loader.bridge

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.ModuleDescriptor
import io.karandash.api.ModuleType
import io.karandash.api.context.ValidationContext
import io.karandash.api.module.FileGranularRuleModule
import io.karandash.api.module.FindingsAwareRuleModule
import io.karandash.api.module.ModuleUsage
import io.karandash.api.module.RuleModule
import io.karandash.api.platform.MergeRequestField
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val json = KarandashJson.Default
private val findingsSerializer = ListSerializer(Finding.serializer())

/**
 * The [RuleModule] proxy for [guest]; it also serves [FileGranularRuleModule] and/or [FindingsAwareRuleModule] when the
 * guest exports `validateFile` / `validateWithFindings`, since the engine picks those paths by `is` checks.
 */
internal fun bridgeRuleProxy(
    guest: RuleModule,
    descriptor: BridgeDescriptor,
    transport: BridgeTransport,
): BridgeRuleProxy {
    val perFile = transport.hasCapability(OP_VALIDATE_FILE)
    val withFindings = transport.hasCapability(OP_VALIDATE_WITH_FINDINGS)
    return when {
        perFile && withFindings -> FileAndFindingsRuleProxy(guest, descriptor, transport)
        perFile -> FileRuleProxy(guest, descriptor, transport)
        withFindings -> FindingsRuleProxy(guest, descriptor, transport)
        else -> BridgeRuleProxy(guest, descriptor, transport)
    }
}

/**
 * [RuleModule] proxy. Enrichment and `defaultEnabled` come from the god bridge (it must override them
 * anyway to settle the Rule/AiAnalyzer diamond); rule-only flags are read from the descriptor here, and the
 * accounting members (`usage`, `requiredMergeRequestFields`, `tokensConsumed`, `logCostSummary`) are guest ops.
 */
@OptIn(ExperimentalKarandashApi::class)
internal open class BridgeRuleProxy(
    guest: RuleModule,
    descriptor: BridgeDescriptor,
    protected val transport: BridgeTransport,
) : RuleModule by guest {
    override val type: ModuleType get() = ModuleType.RULE
    override val defaultEnabled: Boolean = guest.defaultEnabledAs(ModuleType.RULE)
    override val usesExtensionComputedConfig: Boolean =
        descriptor.bool(ModuleDescriptor.USES_EXTENSION_COMPUTED_CONFIG) ?: false
    override val executionTimeout: Duration? =
        descriptor.long(ModuleDescriptor.EXECUTION_TIMEOUT)?.milliseconds ?: guest.executionTimeout

    override val usage: ModuleUsage? get() = transport.querySync(OP_USAGE, ModuleUsage.serializer())

    override fun requiredMergeRequestFields(ruleId: String): Set<MergeRequestField> = requiredFields(ruleId, JsonNull)

    override fun requiredMergeRequestFields(
        ruleId: String,
        config: JsonElement,
    ): Set<MergeRequestField> = requiredFields(ruleId, config)

    override fun tokensConsumed(): Int? = transport.querySync(OP_TOKENS_CONSUMED, Int.serializer())

    /** The guest returns the summary line (plain text) and the host logs it at info. */
    override fun logCostSummary() {
        if (!transport.hasCapability(OP_LOG_COST_SUMMARY)) return
        val line = transport.guarded(OP_LOG_COST_SUMMARY, null) { transport.callSync(OP_LOG_COST_SUMMARY) }
        if (!line.isNullOrBlank() && line != BridgeConstants.NULL_SENTINEL) transport.logInfo(OP_LOG_COST_SUMMARY, line)
    }

    /** Field names this host does not know (a newer SDK's) are dropped. */
    private fun requiredFields(
        ruleId: String,
        config: JsonElement,
    ): Set<MergeRequestField> =
        transport
            .querySync(OP_REQUIRED_FIELDS, ListSerializer(String.serializer()), ruleId, config.toString())
            .orEmpty()
            .mapNotNullTo(mutableSetOf()) { name -> MergeRequestField.entries.find { it.name == name } }
}

private class FileRuleProxy(
    guest: RuleModule,
    descriptor: BridgeDescriptor,
    transport: BridgeTransport,
) : BridgeRuleProxy(guest, descriptor, transport),
    FileGranularRuleModule {
    override suspend fun validateFile(
        ruleId: String,
        file: FileChange,
        mr: MergeRequest,
        config: JsonElement,
    ): List<Finding> = transport.validateFileCall(ruleId, file, mr, config)
}

private class FindingsRuleProxy(
    guest: RuleModule,
    descriptor: BridgeDescriptor,
    transport: BridgeTransport,
) : BridgeRuleProxy(guest, descriptor, transport),
    FindingsAwareRuleModule {
    override suspend fun validateWithFindings(
        ruleId: String,
        mr: MergeRequest,
        configElement: JsonElement,
        context: ValidationContext,
    ): List<Finding> = transport.validateWithFindingsCall(ruleId, mr, configElement, context)
}

private class FileAndFindingsRuleProxy(
    guest: RuleModule,
    descriptor: BridgeDescriptor,
    transport: BridgeTransport,
) : BridgeRuleProxy(guest, descriptor, transport),
    FileGranularRuleModule,
    FindingsAwareRuleModule {
    override suspend fun validateFile(
        ruleId: String,
        file: FileChange,
        mr: MergeRequest,
        config: JsonElement,
    ): List<Finding> = transport.validateFileCall(ruleId, file, mr, config)

    override suspend fun validateWithFindings(
        ruleId: String,
        mr: MergeRequest,
        configElement: JsonElement,
        context: ValidationContext,
    ): List<Finding> = transport.validateWithFindingsCall(ruleId, mr, configElement, context)
}

/** A failing call or a malformed answer reads as no findings (logged), like `validate`. */
private suspend fun BridgeTransport.validateFileCall(
    ruleId: String,
    file: FileChange,
    mr: MergeRequest,
    config: JsonElement,
): List<Finding> =
    query(
        OP_VALIDATE_FILE,
        findingsSerializer,
        ruleId,
        json.encodeToString(FileChange.serializer(), file),
        json.encodeToString(MergeRequest.serializer(), mr),
        config.toString(),
    ).orEmpty()

/** `context_json` is the [ValidationContext] object: `{"priorFindings":[...]}`. */
private suspend fun BridgeTransport.validateWithFindingsCall(
    ruleId: String,
    mr: MergeRequest,
    config: JsonElement,
    context: ValidationContext,
): List<Finding> =
    query(
        OP_VALIDATE_WITH_FINDINGS,
        findingsSerializer,
        ruleId,
        json.encodeToString(MergeRequest.serializer(), mr),
        config.toString(),
        json.encodeToString(ValidationContext.serializer(), context),
    ).orEmpty()

private const val OP_VALIDATE_FILE = "validateFile"
private const val OP_VALIDATE_WITH_FINDINGS = "validateWithFindings"
private const val OP_USAGE = "usage"
private const val OP_REQUIRED_FIELDS = "requiredMergeRequestFields"
private const val OP_TOKENS_CONSUMED = "tokensConsumed"
private const val OP_LOG_COST_SUMMARY = "logCostSummary"
