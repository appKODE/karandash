package io.karandash.loader.bridge

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.Module
import io.karandash.api.ModuleDescriptor
import io.karandash.api.ModuleType
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.api.ai.AiInterceptorModule
import io.karandash.api.ai.AiMetricModule
import io.karandash.api.ai.AiPromptProcessorModule
import io.karandash.api.ai.AiProviderModule
import io.karandash.api.ai.AiRedactorModule
import io.karandash.api.ai.AiReportModule
import io.karandash.api.ai.AiResponseTransformerModule
import io.karandash.api.ai.AiTokenAllocatorModule
import io.karandash.api.ai.ReportInput
import io.karandash.api.module.EnrichedReporterModule
import io.karandash.api.module.EnrichmentProviderModule
import io.karandash.api.module.FormatModule
import io.karandash.api.module.OutputModule
import io.karandash.api.module.PipelineExtension
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.ProfileModule
import io.karandash.api.module.RuleModule
import io.karandash.api.module.ScriptModule
import io.karandash.api.module.TranslationModule
import io.karandash.models.finding.PluralRule

/**
 * Wraps a multi-interface guest bridge (JS/WASM/native) in a proxy that implements only the SDK
 * interface of the guest's declared [Module.type].
 *
 * The god bridges implement every SDK interface, so registering them directly made every guest
 * look like a [RuleModule], a [PlatformServiceModule], … to `is` checks in the host. The proxy
 * also owns the descriptor-backed properties: a new SDK flag costs one override here. Types the god
 * bridges do not implement ([FormatModule], [OutputModule], [PipelineExtension]) are served over
 * [transport] by the proxies in BridgeHostProxies.kt.
 */
internal fun bridgeFor(
    guest: Module,
    descriptor: BridgeDescriptor,
    transport: BridgeTransport,
): Module = typedPart(guest, guest.type.also(descriptor::warnUnservedTypes), descriptor, transport) ?: PlainModuleProxy(guest)

/**
 * The proxy that serves [guest] as [type], or `null` when [type] has no proxy here: CLI_COMMAND and
 * CONFIG_FORMAT are served by the adapters in BridgeRegistrationAdapters.kt, and the types in
 * [BridgeDescriptor.UNSERVED_TYPES] have no SDK interface a guest could implement. The proxy reports
 * [type] as its own [Module.type], so one god bridge yields one part per type it declares.
 */
internal fun typedPart(
    guest: Module,
    type: ModuleType,
    descriptor: BridgeDescriptor,
    transport: BridgeTransport,
): Module? =
    when (type) {
        ModuleType.RULE -> (guest as? RuleModule)?.let { bridgeRuleProxy(it, descriptor, transport) }

        ModuleType.AI_ANALYZER -> (guest as? AiAnalyzerModule)?.let { BridgeAiAnalyzerProxy(it, descriptor) }

        ModuleType.REPORTER -> (guest as? EnrichedReporterModule)?.let { ReporterProxy(it, descriptor) }

        ModuleType.PROFILE -> (guest as? ProfileModule)?.let(::ProfileProxy)

        ModuleType.PLATFORM_SERVICE -> (guest as? PlatformServiceModule)?.let { BridgePlatformServiceProxy(it, transport) }

        ModuleType.ENRICHMENT_PROVIDER -> (guest as? EnrichmentProviderModule)?.let { EnrichmentProviderProxy(it, descriptor, transport) }

        ModuleType.SCRIPT -> (guest as? ScriptModule)?.let(::ScriptProxy)

        ModuleType.AI_PROVIDER, ModuleType.AI_INTERCEPTOR, ModuleType.AI_PROMPT_PROCESSOR, ModuleType.AI_RESPONSE_TRANSFORMER,
        ModuleType.AI_CONTEXT_ENRICHER, ModuleType.AI_REDACTOR, ModuleType.AI_TOKEN_ALLOCATOR, ModuleType.AI_REPORT, ModuleType.AI_METRIC,
        -> aiPart(guest, type, descriptor)

        ModuleType.FORMAT -> BridgeFormatProxy(guest, transport, descriptor)

        ModuleType.OUTPUT -> BridgeOutputProxy(guest, transport, descriptor)

        ModuleType.PIPELINE_EXTENSION -> BridgePipelineExtensionProxy(guest, transport, descriptor)

        ModuleType.TRANSLATION -> BridgeTranslationProxy(guest, descriptor)

        ModuleType.CLI_COMMAND, ModuleType.CONFIG_FORMAT, ModuleType.AI_PIPELINE_STAGE -> null
    }

/** The AI-tier part of [typedPart], split out to keep each dispatch under the complexity limit. */
private fun aiPart(
    guest: Module,
    type: ModuleType,
    descriptor: BridgeDescriptor,
): Module? =
    when (type) {
        ModuleType.AI_PROVIDER -> (guest as? AiProviderModule)?.let { AiProviderProxy(it, descriptor) }
        ModuleType.AI_INTERCEPTOR -> (guest as? AiInterceptorModule)?.let(::AiInterceptorProxy)
        ModuleType.AI_PROMPT_PROCESSOR -> (guest as? AiPromptProcessorModule)?.let(::AiPromptProcessorProxy)
        ModuleType.AI_RESPONSE_TRANSFORMER -> (guest as? AiResponseTransformerModule)?.let(::AiResponseTransformerProxy)
        ModuleType.AI_CONTEXT_ENRICHER -> (guest as? AiContextEnricherModule)?.let { AiContextEnricherProxy(it, descriptor) }
        ModuleType.AI_REDACTOR -> (guest as? AiRedactorModule)?.let(::AiRedactorProxy)
        ModuleType.AI_TOKEN_ALLOCATOR -> (guest as? AiTokenAllocatorModule)?.let(::AiTokenAllocatorProxy)
        ModuleType.AI_REPORT -> (guest as? AiReportModule)?.let { AiReportProxy(it, descriptor) }
        ModuleType.AI_METRIC -> (guest as? AiMetricModule)?.let { AiMetricProxy(it, descriptor) }
        else -> null
    }

/** The guest's `defaultEnabled` as its [type] part: a composite guest's primary type may resolve it differently. */
internal fun Module.defaultEnabledAs(type: ModuleType): Boolean = (this as? PartAware)?.defaultEnabledFor(type) ?: defaultEnabled

/** [AiAnalyzerModule] proxy; analyzer-only flags are read from the descriptor (see [BridgeRuleProxy]). */
@OptIn(ExperimentalKarandashApi::class)
internal class BridgeAiAnalyzerProxy(
    guest: AiAnalyzerModule,
    descriptor: BridgeDescriptor,
) : AiAnalyzerModule by guest {
    override val type: ModuleType get() = ModuleType.AI_ANALYZER
    override val defaultEnabled: Boolean = guest.defaultEnabledAs(ModuleType.AI_ANALYZER)
    override val needsScopeDetection: Boolean = descriptor.bool(ModuleDescriptor.NEEDS_SCOPE_DETECTION) ?: false
    override val needsBlame: Boolean = descriptor.bool(ModuleDescriptor.NEEDS_BLAME) ?: false
    override val mrLevel: Boolean = descriptor.bool(ModuleDescriptor.MR_LEVEL) ?: false
    override val estimatedTokens: Int = descriptor.int(ModuleDescriptor.ESTIMATED_TOKENS) ?: guest.estimatedTokens
    override val explanation: String = descriptor.string(ModuleDescriptor.EXPLANATION) ?: guest.explanation
    override val responseFormatSuffix: String? =
        descriptor.string(ModuleDescriptor.RESPONSE_FORMAT_SUFFIX) ?: guest.responseFormatSuffix
    override val supportsSemiFormalReasoning: Boolean =
        descriptor.bool(ModuleDescriptor.SUPPORTS_SEMI_FORMAL_REASONING) ?: guest.supportsSemiFormalReasoning
}

/** [EnrichedReporterModule] proxy; reporter flags come from the descriptor, else the SDK defaults. */
private class ReporterProxy(
    private val guest: EnrichedReporterModule,
    descriptor: BridgeDescriptor,
) : EnrichedReporterModule by guest {
    override val type: ModuleType get() = ModuleType.REPORTER
    override val defaultEnabled: Boolean = guest.defaultEnabledAs(ModuleType.REPORTER)
    override val formatId: String get() = (guest as? PartAware)?.formatIdFor(ModuleType.REPORTER) ?: guest.formatId
    override val requiresPlatformContext: Boolean =
        descriptor.bool(ModuleDescriptor.REQUIRES_PLATFORM_CONTEXT) ?: guest.requiresPlatformContext
    override val supportsLiveMode: Boolean = descriptor.bool(ModuleDescriptor.SUPPORTS_LIVE_MODE) ?: guest.supportsLiveMode
    override val hasExternalSideEffects: Boolean =
        descriptor.bool(ModuleDescriptor.HAS_EXTERNAL_SIDE_EFFECTS) ?: guest.hasExternalSideEffects
    override val isDefaultReporter: Boolean = descriptor.bool(ModuleDescriptor.IS_DEFAULT_REPORTER) ?: guest.isDefaultReporter
    override val isDefaultInteractive: Boolean =
        descriptor.bool(ModuleDescriptor.IS_DEFAULT_INTERACTIVE) ?: guest.isDefaultInteractive
    override val isDefaultCI: Boolean = descriptor.bool(ModuleDescriptor.IS_DEFAULT_CI) ?: guest.isDefaultCI
    override val supportsFileOutput: Boolean = descriptor.bool(ModuleDescriptor.SUPPORTS_FILE_OUTPUT) ?: guest.supportsFileOutput
}

private class ProfileProxy(
    guest: ProfileModule,
) : ProfileModule by guest {
    override val type: ModuleType get() = ModuleType.PROFILE
}

/**
 * [EnrichmentProviderModule] proxy; provider dependencies come from the descriptor's `requiredEnrichment`, the
 * skip reason (plain text, read after `enrich`) from the guest's `skipReason` op.
 */
private class EnrichmentProviderProxy(
    guest: EnrichmentProviderModule,
    descriptor: BridgeDescriptor,
    private val transport: BridgeTransport,
) : EnrichmentProviderModule by guest {
    override val type: ModuleType get() = ModuleType.ENRICHMENT_PROVIDER
    private val required: List<String> = descriptor.stringList(ModuleDescriptor.REQUIRED_ENRICHMENT) ?: guest.requiredEnrichment()

    override fun requiredEnrichment(): List<String> = required

    override fun skipReason(): String? {
        if (!transport.hasCapability(OP_SKIP_REASON)) return null
        return transport
            .guarded(OP_SKIP_REASON, null) { transport.callSync(OP_SKIP_REASON) }
            ?.takeUnless { it.isBlank() || it == BridgeConstants.NULL_SENTINEL }
    }

    private companion object {
        const val OP_SKIP_REASON = "skipReason"
    }
}

private class ScriptProxy(
    guest: ScriptModule,
) : ScriptModule by guest {
    override val type: ModuleType get() = ModuleType.SCRIPT
}

@OptIn(ExperimentalKarandashApi::class)
private class AiProviderProxy(
    guest: AiProviderModule,
    descriptor: BridgeDescriptor,
) : AiProviderModule by guest {
    override val type: ModuleType get() = ModuleType.AI_PROVIDER
    override val requiresApiKey: Boolean = descriptor.bool(ModuleDescriptor.REQUIRES_API_KEY) ?: guest.requiresApiKey
}

private class AiInterceptorProxy(
    guest: AiInterceptorModule,
) : AiInterceptorModule by guest {
    override val type: ModuleType get() = ModuleType.AI_INTERCEPTOR
}

private class AiPromptProcessorProxy(
    guest: AiPromptProcessorModule,
) : AiPromptProcessorModule by guest {
    override val type: ModuleType get() = ModuleType.AI_PROMPT_PROCESSOR
}

private class AiResponseTransformerProxy(
    guest: AiResponseTransformerModule,
) : AiResponseTransformerModule by guest {
    override val type: ModuleType get() = ModuleType.AI_RESPONSE_TRANSFORMER
}

@OptIn(ExperimentalKarandashApi::class)
private class AiContextEnricherProxy(
    guest: AiContextEnricherModule,
    descriptor: BridgeDescriptor,
) : AiContextEnricherModule by guest {
    override val type: ModuleType get() = ModuleType.AI_CONTEXT_ENRICHER
    private val handled: List<String> =
        descriptor.stringList(ModuleDescriptor.HANDLED_ENRICHMENT_IDS) ?: guest.handledEnrichmentIds()

    override fun handledEnrichmentIds(): List<String> = handled
}

private class AiRedactorProxy(
    guest: AiRedactorModule,
) : AiRedactorModule by guest {
    override val type: ModuleType get() = ModuleType.AI_REDACTOR
}

private class AiTokenAllocatorProxy(
    guest: AiTokenAllocatorModule,
) : AiTokenAllocatorModule by guest {
    override val type: ModuleType get() = ModuleType.AI_TOKEN_ALLOCATOR
}

private class AiReportProxy(
    guest: AiReportModule,
    descriptor: BridgeDescriptor,
) : AiReportModule by guest {
    override val type: ModuleType get() = ModuleType.AI_REPORT
    override val requiredInputs: Set<ReportInput> = descriptor.reportInputs() ?: guest.requiredInputs
}

private class AiMetricProxy(
    guest: AiMetricModule,
    descriptor: BridgeDescriptor,
) : AiMetricModule by guest {
    override val type: ModuleType get() = ModuleType.AI_METRIC
    override val requiredInputs: Set<ReportInput> = descriptor.reportInputs() ?: guest.requiredInputs
}

/** `requiredInputs` from the descriptor; unknown names (a newer SDK's inputs) are dropped. */
private fun BridgeDescriptor.reportInputs(): Set<ReportInput>? =
    stringList(ModuleDescriptor.REQUIRED_INPUTS)?.mapNotNull { name -> ReportInput.entries.find { it.name == name } }?.toSet()

internal class PlainModuleProxy(
    guest: Module,
) : Module by guest

/** [TranslationModule] proxy: a language pack is plain data, read from the descriptor (no bridge op). */
internal class BridgeTranslationProxy(
    guest: Module,
    descriptor: BridgeDescriptor,
) : TranslationModule,
    Module by guest {
    override val type: ModuleType get() = ModuleType.TRANSLATION
    override val language: String = descriptor.string(ModuleDescriptor.LANGUAGE).orEmpty()
    override val pluralRule: PluralRule =
        descriptor.string(ModuleDescriptor.PLURAL_RULE)?.let { rule -> PluralRule.entries.firstOrNull { it.name == rule } }
            ?: PluralRule.ONE_OTHER
    private val texts: Map<String, String> = descriptor.stringMap(ModuleDescriptor.TRANSLATIONS).orEmpty()

    override fun translations(): Map<String, String> = texts
}
