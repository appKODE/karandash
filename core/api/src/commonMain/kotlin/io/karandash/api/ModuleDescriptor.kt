package io.karandash.api

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
import io.karandash.api.module.CliCommandModule
import io.karandash.api.module.ConfigFormatModule
import io.karandash.api.module.EnrichmentProviderModule
import io.karandash.api.module.FormatModule
import io.karandash.api.module.OutputModule
import io.karandash.api.module.PipelineExtension
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.ProfileModule
import io.karandash.api.module.ReporterModule
import io.karandash.api.module.RuleModule
import io.karandash.api.module.ScriptModule
import io.karandash.api.module.TranslationModule
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Self-description a guest plugin (JS, WASM, native, JAR subprocess) hands to the host in one
 * `describe()` call.
 *
 * Bridges read SDK properties from [properties] (keyed by the Kotlin property name, e.g.
 * `"supportedEnrichment"`) and fall back to the SDK default when a key is absent, so a new SDK
 * property never needs a new ABI export - only one entry here and one read in the host's
 * per-type bridge proxy. Guests built against an older SDK simply omit the key.
 *
 * @property id Module ID; must match [Module.id].
 * @property types Module type names ([ModuleType.name]) the guest implements, [Module.type] first; a composite
 *   module (one class implementing several module interfaces) lists each.
 * @property sdkVersion Descriptor format version, [CURRENT_SDK_VERSION] today.
 * @property properties SDK property values by name; absent key = SDK default.
 */
@ExperimentalKarandashApi
@Serializable
public data class ModuleDescriptor(
    public val id: String,
    public val types: List<String>,
    public val sdkVersion: String = CURRENT_SDK_VERSION,
    public val properties: JsonObject = JsonObject(emptyMap()),
) {
    public companion object {
        public const val CURRENT_SDK_VERSION: String = "1"

        /** [Module.defaultEnabled]. */
        public const val DEFAULT_ENABLED: String = "defaultEnabled"

        /** [RuleModule.supportedEnrichment] / [AiAnalyzerModule.supportedEnrichment]. */
        public const val SUPPORTED_ENRICHMENT: String = "supportedEnrichment"

        /** [RuleModule.requiredEnrichment] / [AiAnalyzerModule.requiredEnrichment]. */
        public const val REQUIRED_ENRICHMENT: String = "requiredEnrichment"

        /** [RuleModule.usesExtensionComputedConfig]. */
        public const val USES_EXTENSION_COMPUTED_CONFIG: String = "usesExtensionComputedConfig"

        /** [AiAnalyzerModule.needsScopeDetection]. */
        public const val NEEDS_SCOPE_DETECTION: String = "needsScopeDetection"

        /** [AiAnalyzerModule.needsBlame] / [PipelineExtension.needsBlame] (a guest declares it statically). */
        public const val NEEDS_BLAME: String = "needsBlame"

        /** [AiAnalyzerModule.mrLevel]. */
        public const val MR_LEVEL: String = "mrLevel"

        /** [PipelineExtension.needsHeadContent] (a guest declares it statically). */
        public const val NEEDS_HEAD_CONTENT: String = "needsHeadContent"

        /** [Module.defaultEnabled] of an [AiAnalyzerModule]. */
        public const val ENABLED_BY_DEFAULT: String = "enabledByDefault"

        /** [FormatModule.isDefaultInteractive] / [OutputModule.isDefaultInteractive]. */
        public const val IS_DEFAULT_INTERACTIVE: String = "isDefaultInteractive"

        /** [FormatModule.isDefaultPlatform] / [OutputModule.isDefaultPlatform]. */
        public const val IS_DEFAULT_PLATFORM: String = "isDefaultPlatform"

        /** [OutputModule.acceptedFormats]. */
        public const val ACCEPTED_FORMATS: String = "acceptedFormats"

        /** [OutputModule.requiresPlatformContext]. */
        public const val REQUIRES_PLATFORM_CONTEXT: String = "requiresPlatformContext"

        /** [OutputModule.supportsFileOutput]. */
        public const val SUPPORTS_FILE_OUTPUT: String = "supportsFileOutput"

        /** [OutputModule.preferredFormat]. */
        public const val PREFERRED_FORMAT: String = "preferredFormat"

        /** [OutputModule.hasExternalSideEffects]. */
        public const val HAS_EXTERNAL_SIDE_EFFECTS: String = "hasExternalSideEffects"

        /** [AiReportModule.requiredInputs] / [AiMetricModule.requiredInputs] ([ReportInput] names). */
        public const val REQUIRED_INPUTS: String = "requiredInputs"

        /** [ReporterModule.isDefaultCI]. */
        public const val IS_DEFAULT_CI: String = "isDefaultCI"

        /** [ReporterModule.isDefaultReporter]. */
        public const val IS_DEFAULT_REPORTER: String = "isDefaultReporter"

        /** [ReporterModule.supportsLiveMode]. */
        public const val SUPPORTS_LIVE_MODE: String = "supportsLiveMode"

        /** [RuleModule.executionTimeout], in whole milliseconds; `null` = the engine default. */
        public const val EXECUTION_TIMEOUT: String = "executionTimeout"

        /** [AiAnalyzerModule.estimatedTokens]. */
        public const val ESTIMATED_TOKENS: String = "estimatedTokens"

        /** [AiAnalyzerModule.explanation]. */
        public const val EXPLANATION: String = "explanation"

        /** [AiAnalyzerModule.responseFormatSuffix]. */
        public const val RESPONSE_FORMAT_SUFFIX: String = "responseFormatSuffix"

        /** [AiAnalyzerModule.supportsSemiFormalReasoning]. */
        public const val SUPPORTS_SEMI_FORMAL_REASONING: String = "supportsSemiFormalReasoning"

        /** [AiProviderModule.requiresApiKey]. */
        public const val REQUIRES_API_KEY: String = "requiresApiKey"

        /** [AiContextEnricherModule.handledEnrichmentIds]. */
        public const val HANDLED_ENRICHMENT_IDS: String = "handledEnrichmentIds"

        /** [ConfigFormatModule.priority]. */
        public const val PRIORITY: String = "priority"

        /** [Module.dslName]; absent means the module ID. */
        public const val DSL_NAME: String = "dslName"

        /** [Module.hoverDocumentation]; absent or `null` means none. */
        public const val HOVER_DOCUMENTATION: String = "hoverDocumentation"

        /** [TranslationModule.language]. */
        public const val LANGUAGE: String = "language"

        /** [TranslationModule.pluralRule], by enum name. */
        public const val PLURAL_RULE: String = "pluralRule"

        /** [TranslationModule.translations], a JSON object of English template to translated template. */
        public const val TRANSLATIONS: String = "translations"

        /**
         * Builds the descriptor of an in-process [module]. Used by guest-side SDK glue
         * (generated native exports, the JAR bridge server) to answer `describe()`.
         */
        public fun of(module: Module): ModuleDescriptor {
            val props = mutableMapOf<String, JsonElement>(
                DEFAULT_ENABLED to JsonPrimitive(module.defaultEnabled),
                DSL_NAME to JsonPrimitive(module.dslName),
                HOVER_DOCUMENTATION to JsonPrimitive(module.hoverDocumentation),
            )
            if (module is RuleModule) {
                props[SUPPORTED_ENRICHMENT] = module.supportedEnrichment.toJsonArray()
                props[REQUIRED_ENRICHMENT] = module.requiredEnrichment.toJsonArray()
                props[USES_EXTENSION_COMPUTED_CONFIG] = JsonPrimitive(module.usesExtensionComputedConfig)
                props[EXECUTION_TIMEOUT] = JsonPrimitive(module.executionTimeout?.inWholeMilliseconds)
            }
            if (module is AiAnalyzerModule) {
                props[SUPPORTED_ENRICHMENT] = module.supportedEnrichment.toJsonArray()
                props[REQUIRED_ENRICHMENT] = module.requiredEnrichment.toJsonArray()
                props[NEEDS_SCOPE_DETECTION] = JsonPrimitive(module.needsScopeDetection)
                props[NEEDS_BLAME] = JsonPrimitive(module.needsBlame)
                props[MR_LEVEL] = JsonPrimitive(module.mrLevel)
                props[ENABLED_BY_DEFAULT] = JsonPrimitive(module.defaultEnabled)
                props[ESTIMATED_TOKENS] = JsonPrimitive(module.estimatedTokens)
                props[EXPLANATION] = JsonPrimitive(module.explanation)
                props[RESPONSE_FORMAT_SUFFIX] = JsonPrimitive(module.responseFormatSuffix)
                props[SUPPORTS_SEMI_FORMAL_REASONING] = JsonPrimitive(module.supportsSemiFormalReasoning)
            }
            // A composite module's flags merge; where two interfaces share a key the first listed here wins.
            hostFlags(module).forEach { flags -> flags.forEach { (key, value) -> props.getOrPut(key) { value } } }
            return ModuleDescriptor(id = module.id, types = typesOf(module).map { it.name }, properties = JsonObject(props))
        }

        /** Interfaces whose module type a composite module declares besides [Module.type]. */
        private val typeInterfaces = listOf(
            ModuleType.RULE to RuleModule::class,
            ModuleType.REPORTER to ReporterModule::class,
            ModuleType.CLI_COMMAND to CliCommandModule::class,
            ModuleType.CONFIG_FORMAT to ConfigFormatModule::class,
            ModuleType.PROFILE to ProfileModule::class,
            ModuleType.AI_PROVIDER to AiProviderModule::class,
            ModuleType.AI_INTERCEPTOR to AiInterceptorModule::class,
            ModuleType.AI_ANALYZER to AiAnalyzerModule::class,
            ModuleType.AI_PROMPT_PROCESSOR to AiPromptProcessorModule::class,
            ModuleType.AI_RESPONSE_TRANSFORMER to AiResponseTransformerModule::class,
            ModuleType.AI_CONTEXT_ENRICHER to AiContextEnricherModule::class,
            ModuleType.AI_REDACTOR to AiRedactorModule::class,
            ModuleType.AI_TOKEN_ALLOCATOR to AiTokenAllocatorModule::class,
            ModuleType.SCRIPT to ScriptModule::class,
            ModuleType.PIPELINE_EXTENSION to PipelineExtension::class,
            ModuleType.PLATFORM_SERVICE to PlatformServiceModule::class,
            ModuleType.ENRICHMENT_PROVIDER to EnrichmentProviderModule::class,
            ModuleType.FORMAT to FormatModule::class,
            ModuleType.OUTPUT to OutputModule::class,
            ModuleType.AI_REPORT to AiReportModule::class,
            ModuleType.AI_METRIC to AiMetricModule::class,
            ModuleType.TRANSLATION to TranslationModule::class,
        )

        private fun typesOf(module: Module): List<ModuleType> =
            (listOf(module.type) + typeInterfaces.filter { (_, iface) -> iface.isInstance(module) }.map { it.first }).distinct()

        /** Flag groups of every interface [module] implements, [Module.type]'s first. */
        private fun hostFlags(module: Module): List<Map<String, JsonElement>> = typesOf(module).map { hostFlags(module, it) }

        private fun hostFlags(
            module: Module,
            type: ModuleType,
        ): Map<String, JsonElement> =
            when {
                type == ModuleType.FORMAT && module is FormatModule -> mapOf(
                    IS_DEFAULT_INTERACTIVE to JsonPrimitive(module.isDefaultInteractive),
                    IS_DEFAULT_PLATFORM to JsonPrimitive(module.isDefaultPlatform),
                )

                type == ModuleType.OUTPUT && module is OutputModule -> mapOf(
                    IS_DEFAULT_INTERACTIVE to JsonPrimitive(module.isDefaultInteractive),
                    IS_DEFAULT_PLATFORM to JsonPrimitive(module.isDefaultPlatform),
                    ACCEPTED_FORMATS to module.acceptedFormats.toList().toJsonArray(),
                    REQUIRES_PLATFORM_CONTEXT to JsonPrimitive(module.requiresPlatformContext),
                    SUPPORTS_FILE_OUTPUT to JsonPrimitive(module.supportsFileOutput),
                    PREFERRED_FORMAT to JsonPrimitive(module.preferredFormat),
                    HAS_EXTERNAL_SIDE_EFFECTS to JsonPrimitive(module.hasExternalSideEffects),
                )

                type == ModuleType.REPORTER && module is ReporterModule -> mapOf(
                    IS_DEFAULT_INTERACTIVE to JsonPrimitive(module.isDefaultInteractive),
                    IS_DEFAULT_CI to JsonPrimitive(module.isDefaultCI),
                    IS_DEFAULT_REPORTER to JsonPrimitive(module.isDefaultReporter),
                    SUPPORTS_LIVE_MODE to JsonPrimitive(module.supportsLiveMode),
                    REQUIRES_PLATFORM_CONTEXT to JsonPrimitive(module.requiresPlatformContext),
                    SUPPORTS_FILE_OUTPUT to JsonPrimitive(module.supportsFileOutput),
                    HAS_EXTERNAL_SIDE_EFFECTS to JsonPrimitive(module.hasExternalSideEffects),
                )

                type == ModuleType.PIPELINE_EXTENSION && module is PipelineExtension -> mapOf(
                    NEEDS_BLAME to JsonPrimitive(module.needsBlame(null)),
                    NEEDS_HEAD_CONTENT to JsonPrimitive(module.needsHeadContent(null)),
                )

                type == ModuleType.ENRICHMENT_PROVIDER && module is EnrichmentProviderModule -> mapOf(
                    REQUIRED_ENRICHMENT to module.requiredEnrichment().toJsonArray()
                )

                type == ModuleType.AI_PROVIDER && module is AiProviderModule -> mapOf(
                    REQUIRES_API_KEY to JsonPrimitive(module.requiresApiKey)
                )

                type == ModuleType.AI_CONTEXT_ENRICHER && module is AiContextEnricherModule -> mapOf(
                    HANDLED_ENRICHMENT_IDS to module.handledEnrichmentIds().toJsonArray()
                )

                type == ModuleType.CONFIG_FORMAT && module is ConfigFormatModule -> mapOf(
                    PRIORITY to JsonPrimitive(module.priority)
                )

                type == ModuleType.AI_REPORT && module is AiReportModule -> mapOf(
                    REQUIRED_INPUTS to module.requiredInputs.map { it.name }.toJsonArray()
                )

                type == ModuleType.AI_METRIC && module is AiMetricModule -> mapOf(
                    REQUIRED_INPUTS to module.requiredInputs.map { it.name }.toJsonArray()
                )

                type == ModuleType.TRANSLATION && module is TranslationModule -> mapOf(
                    LANGUAGE to JsonPrimitive(module.language),
                    PLURAL_RULE to JsonPrimitive(module.pluralRule.name),
                    TRANSLATIONS to JsonObject(module.translations().mapValues { JsonPrimitive(it.value) }),
                )

                else -> emptyMap()
            }

        private fun List<String>.toJsonArray(): JsonArray = JsonArray(map(::JsonPrimitive))
    }
}
