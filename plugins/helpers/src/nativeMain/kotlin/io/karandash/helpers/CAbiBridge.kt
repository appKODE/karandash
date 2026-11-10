@file:OptIn(ExperimentalForeignApi::class)

package io.karandash.helpers

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.Module
import io.karandash.api.ModuleDescriptor
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.ai.AiTokenBudget
import io.karandash.api.module.ReporterModule
import io.karandash.api.module.RuleModule
import io.karandash.helpers.guest.GuestCodec
import io.karandash.helpers.guest.GuestOpTable
import io.karandash.interpolation.KarandashEnvSecurity
import io.karandash.models.MergeRequest
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.context.BridgeHostSettings
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import platform.posix.free
import platform.posix.strdup

/**
 * Shared C ABI plumbing for distributable native plugin modules.
 *
 * Provides string memory management ([toCString] / [freeString]), the context reader and the rule/reporter
 * dispatch wrappers ([wrapValidate], [wrapReport]) that translate between the C ABI JSON string contract and
 * strongly-typed Kotlin SDK interfaces; platform-service wrappers live in [CAbiPlatformBridge]. Every wrapper runs
 * through [call].
 *
 * All strings returned from these functions must be freed by the caller via
 * [freeString] (or the exported `karandash_free_string` symbol).
 */
object CAbiBridge {
    private val json = KarandashJson.Default

    /**
     * Plugin coroutines run on this dedicated pool, never on the host thread blocked in the C call, so a host that
     * calls in from its own event-loop or main thread cannot deadlock against work the plugin dispatches.
     */
    internal val bridgeContext = Dispatchers.IO.limitedParallelism(BRIDGE_PARALLELISM, "karandash-cabi-bridge")

    private const val BRIDGE_PARALLELISM = 8

    /**
     * Allocate a null-terminated C string on the native heap via `strdup`.
     * The caller must free the returned pointer with [freeString].
     */
    fun toCString(str: String): CPointer<ByteVar> = strdup(str) ?: error("strdup returned null — out of memory allocating: $str")

    /**
     * Answers the `karandash_describe` export: the [ModuleDescriptor] of [module] as a C JSON string.
     */
    @OptIn(ExperimentalKarandashApi::class)
    fun describe(module: Module): CPointer<ByteVar> =
        toCString(json.encodeToString(ModuleDescriptor.serializer(), ModuleDescriptor.of(module)))

    /**
     * Free a string previously allocated by [toCString].
     */
    fun freeString(ptr: CPointer<ByteVar>?) {
        free(ptr)
    }

    /**
     * Read a nullable C string pointer, returning an empty string for null.
     */
    fun CPointer<ByteVar>?.toKStringOrEmpty(): String = this?.toKString() ?: ""

    /**
     * Deserialize the context JSON produced by the host's native bridge (`NativeModuleBridge` on JVM,
     * `TransportModuleBridge` on the native host)
     * into a [PluginContext] suitable for [io.karandash.api.Module.initialize].
     *
     * Expected JSON shape:
     * ```json
     * { "pluginConfig": {…}, "hostVersion": "1.0.0", "workingDirectory": "/path", "configDir": "/path",
     *   "allowLocalhost": false, "shortShaLength": 8, "patterns": {…}, "env": {…} }
     * ```
     * Host display settings are applied to this guest process via [BridgeHostSettings]. [PluginContext.env] answers
     * only from the host-supplied `env` object, never from this process's own environment, minus
     * [KarandashEnvSecurity.DENYLIST] and [KarandashEnvSecurity.isSensitive] names.
     */
    fun contextFromJson(ctxJson: CPointer<ByteVar>?): PluginContext {
        val jsonStr = ctxJson?.toKString() ?: return CAbiPluginContext.EMPTY
        return try {
            val obj = json.parseToJsonElement(jsonStr).jsonObject
            BridgeHostSettings.apply(obj)
            CAbiPluginContext(obj)
        } catch (_: Exception) {
            // boundary: C ABI export must not throw across the FFI
            CAbiPluginContext.EMPTY
        }
    }

    /**
     * Validate a merge request using a rule module and return a JSON findings array.
     * Returns an empty array on deserialization error.
     */
    fun wrapValidate(
        module: RuleModule,
        ruleId: CPointer<ByteVar>?,
        mrJson: CPointer<ByteVar>?,
        configJson: CPointer<ByteVar>?
    ): CPointer<ByteVar> =
        cabiStr {
            // a blank or invalid config reads as `{}`, as it always has for K/N guests
            val config = parseConfigJsonElement(configJson.toKStringOrEmpty()).toString()
            GuestOpTable.run(module, "validate", ruleId.toKStringOrEmpty(), mrJson.toKStringOrEmpty(), config)
        } ?: toCString("[]")

    /**
     * Serialize a set of contributed rule IDs to a JSON array C string.
     */
    fun serializeRules(rules: Set<String>): CPointer<ByteVar> = toCString(json.encodeToString(rules.toList()))

    /**
     * Invoke a reporter module's [ReporterModule.report] and return the formatted
     * output as a C string, or null if the reporter produced no output.
     */
    fun wrapReport(
        module: ReporterModule,
        findingsJson: CPointer<ByteVar>?,
        passed: Int,
        configJson: CPointer<ByteVar>?
    ): CPointer<ByteVar>? =
        cabiStr {
            val config = parseConfigJsonElement(configJson.toKStringOrEmpty()).toString()
            GuestOpTable.run(module, "report", findingsJson.toKStringOrEmpty(), passed.toString(), config)
        }

    /**
     * Answers `karandash_ai_analyze(mr_json, budget_json, model, config_json)`: [module] analyzes with the host's
     * `ai.complete` (its provider, budget and context blocks) and returns a findings JSON array.
     */
    fun wrapAnalyze(
        module: AiAnalyzerModule,
        mrJson: CPointer<ByteVar>?,
        budgetJson: CPointer<ByteVar>?,
        model: CPointer<ByteVar>?,
        configJson: CPointer<ByteVar>?,
    ): CPointer<ByteVar> =
        call(null) {
            val mr = json.decodeFromString<MergeRequest>(mrJson.toKStringOrEmpty())
            val config = parseConfigJsonElement(configJson.toKStringOrEmpty()) as? JsonObject ?: JsonObject(emptyMap())
            val remaining = (parseConfigJsonElement(budgetJson.toKStringOrEmpty()) as? JsonObject)?.int("remaining") ?: 0
            val context =
                AiAnalyzerContext(
                    mr = mr,
                    aiService = HostAiService(isAvailable = (config["aiServiceAvailable"] as? JsonPrimitive)?.booleanOrNull ?: false),
                    budget = HostBudget(remaining),
                    model = model.toKStringOrEmpty(),
                    config = JsonObject(config - analyzeHostKeys),
                    extraInstructions = config.str("instructions"),
                    resolvedPrompt = config.str("resolvedPrompt"),
                    // the host's `skip` service reports it on the host's analyzer context
                    onSkip = { reason -> CAbiHost.request("skip", buildJsonObject { put("reason", reason) }) },
                )
            toCString(json.encodeToString(module.analyze(context)))
        } ?: toCString("[]")

    /** Keys the host adds to the rule config of `karandash_ai_analyze` (`analyzerConfigJson`). */
    private val analyzeHostKeys = setOf("aiServiceAvailable", "extraInstructions", "instructions", "resolvedPrompt")

    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull

    /** The host keeps the real budget (its `ai.complete` records spend); the guest sees what was left at the call. */
    private class HostBudget(
        private val remaining: Int,
    ) : AiTokenBudget {
        override fun canSpend(estimatedTokens: Int) = estimatedTokens <= remaining

        override fun recordSpend(tokens: Int) = Unit

        override fun remaining() = remaining

        override fun isExhausted() = remaining <= 0
    }

    /**
     * Runs [block] on [bridgeContext] for a C ABI export and answers [fallback] when it throws: nothing may cross the
     * FFI as an exception. Every K/N guest export goes through here.
     */
    fun <T> call(
        fallback: T,
        block: suspend () -> T,
    ): T =
        try {
            // bridge-boundary: C ABI export cannot suspend
            runBlocking(bridgeContext) { block() }
        } catch (_: Exception) {
            // boundary: C ABI export must not throw across the FFI
            fallback
        }

    /** [call] for an INT/BOOL export answered as a string op result ([GuestCodec.intArg]); 0 on failure or no answer. */
    fun cabiInt(block: suspend () -> String?): Int = call(0) { block()?.let(GuestCodec::intArg) ?: 0 }

    /** [call] for a STR export: the op result as a C string to free with [freeString]; null on failure or no answer. */
    fun cabiStr(block: suspend () -> String?): CPointer<ByteVar>? = call(null) { block() }?.let(::toCString)

    private fun parseConfigJsonElement(jsonStr: String): JsonElement {
        if (jsonStr.isBlank()) return JsonObject(emptyMap())
        return try {
            json.parseToJsonElement(jsonStr)
        } catch (_: Exception) {
            // boundary: C ABI export must not throw across the FFI
            JsonObject(emptyMap())
        }
    }
}
