package io.karandash.loader.bridge.ai

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.loader.bridge.BridgeModuleCore
import io.karandash.loader.bridge.BridgePart
import io.karandash.loader.bridge.BridgeTransport
import io.karandash.loader.bridge.guarded
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The `config_json` argument of `karandash_ai_analyze` on every transport: the rule config plus the prompt
 * pipeline's output.
 */
internal fun analyzerConfigJson(context: AiAnalyzerContext): String =
    buildJsonObject {
        (context.config as? JsonObject)?.forEach { (key, value) -> put(key, value) }
        put("aiServiceAvailable", context.aiService.isAvailable)
        // A guest that calls its own provider gets the shared MR context in extraInstructions; one that
        // uses the host's `ai.complete` takes `instructions`, as that service adds the context blocks.
        val extra = context.contextBlocks.map { it.content } + listOfNotNull(context.extraInstructions)
        if (extra.isNotEmpty()) put("extraInstructions", extra.joinToString("\n\n"))
        context.extraInstructions?.let { put("instructions", it) }
        context.resolvedPrompt?.let { put("resolvedPrompt", it) }
    }.toString()

/**
 * Shared [AiAnalyzerModule] implementation using [BridgeTransport].
 */
internal class BridgeAiAnalyzerModule(
    transport: BridgeTransport,
) : BridgePart(transport),
    AiAnalyzerModule {
    override val type: ModuleType get() = ModuleType.AI_ANALYZER
    private val json = KarandashJson.Default

    override val analyzerId: String get() = transport.getProperty(PROP_ANALYZER_ID).ifEmpty { id }
    override val featureKey: String get() = transport.getProperty(PROP_FEATURE_KEY).ifEmpty { analyzerId }
    override val defaultPrompt: String? get() = transport.getProperty(PROP_DEFAULT_PROMPT).takeIf { it.isNotEmpty() }
    override val defaultEnabled: Boolean get() = transport.getBoolProperty(PROP_ENABLED_BY_DEFAULT, false)

    override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
        if (!transport.hasCapability(METHOD_ANALYZE)) return emptyList()
        return transport.guarded(METHOD_ANALYZE, emptyList()) {
            // ABI: karandash_ai_analyze(mr_json, budget_json, model, config_json); the prompt pipeline's
            // output rides in config_json next to the rule config.
            val budgetJson = buildJsonObject { put("remaining", context.budget.remaining()) }.toString()
            val configJson = analyzerConfigJson(context)
            val mrJson = json.encodeToString(MergeRequest.serializer(), context.mr)
            val call = suspend { transport.call(METHOD_ANALYZE, mrJson, budgetJson, context.model, configJson) }
            // the guest's `ai.complete` host calls use this call's AI service and budget
            val host = transport.host
            val result = (if (host != null) host.withAnalyzer(context, call) else call()) ?: return emptyList()
            val answer = json.parseToJsonElement(result)
            // `{"skip": reason}`: the guest declined this run , the same as `context.skip(reason)`
            val skipped = ((answer as? JsonObject)?.get(RESULT_SKIP) as? JsonPrimitive)?.contentOrNull
            if (skipped != null) context.skip(skipped) else json.decodeFromJsonElement(ListSerializer(Finding.serializer()), answer)
        }
    }

    private val lifecycle by lazy { BridgeModuleCore(transport) }

    /** Runs the guest's own `initialize` with the host context (absent counts as success), like every other bridge. */
    override suspend fun initialize(context: PluginContext): Boolean = lifecycle.initialize(context)

    override suspend fun shutdown() = lifecycle.shutdown()

    private companion object {
        const val PROP_ANALYZER_ID = "analyzerId"
        const val PROP_FEATURE_KEY = "featureKey"
        const val PROP_DEFAULT_PROMPT = "defaultPrompt"
        const val PROP_ENABLED_BY_DEFAULT = "enabledByDefault"
        const val METHOD_ANALYZE = "aiAnalyze"
        const val RESULT_SKIP = "skip"
    }
}
