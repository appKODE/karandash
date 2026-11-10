package io.karandash.loader.js

import com.dokar.quickjs.QuickJs
import io.karandash.api.KarandashApi
import io.karandash.api.ModuleType
import io.karandash.api.support.rethrowCancellation
import kotlinx.serialization.Serializable

/**
 * Capabilities detected from a JS plugin's exported object.
 *
 * Each boolean indicates whether the JS plugin object exposes the corresponding
 * function or property. Eagerly-read values (priorities, IDs) are captured here
 * to avoid repeated JS evaluation.
 */
@Serializable
internal data class JsModuleCapabilities(
    val hasInitialize: Boolean,
    val hasShutdown: Boolean,
    val hasValidate: Boolean,
    val hasContributedRules: Boolean,
    val hasReport: Boolean,
    val hasReportEnriched: Boolean = false,
    val hasFetchMergeRequest: Boolean,
    val hasFetchMergeRequestByUrl: Boolean,
    val hasAddLabels: Boolean,
    val hasRemoveLabels: Boolean,
    val hasSetDraft: Boolean,
    val hasUpdateTitle: Boolean,
    val hasUpdateDescription: Boolean,
    val hasApprove: Boolean,
    val hasUnapprove: Boolean,
    val hasPostComment: Boolean,
    val hasPostInlineComment: Boolean,
    val hasUpdateComment: Boolean,
    val hasFindExistingComment: Boolean,
    val hasDeleteMarkedInlineComments: Boolean,
    val hasResolveMarkedInlineComments: Boolean,
    val hasFindAllMatchingComments: Boolean,
    val hasDeleteComment: Boolean,
    val hasRenderSuggestionBlock: Boolean,
    val hasDefaultPriority: Boolean,
    val hasIsDefaultEnabled: Boolean,
    val hasDefaultConfig: Boolean,
    val hasMissingAuthMessage: Boolean,
    val hasCliCommands: Boolean,
    val hasConfigParse: Boolean,
    val hasConfigParseExtends: Boolean,
    val hasRuleConfigs: Boolean,
    val hasReporterConfigs: Boolean,
    val hasAiComplete: Boolean,
    val hasAiIntercept: Boolean,
    val hasAiAnalyze: Boolean,
    val hasAiProcessPrompt: Boolean,
    val hasAiProcessSystemPrompt: Boolean,
    val hasAiTransformResponse: Boolean,
    val hasAiEnrichContext: Boolean,
    val hasAiProvideBlock: Boolean,
    val hasAiRedact: Boolean,
    val hasAiAllocateTokens: Boolean,
    val hasExecuteScripts: Boolean,
    val hasRuleExplanation: Boolean,
    val hasRuntimeReporterConfig: Boolean,
    val hasConfigSchema: Boolean,
    val hasDetectCiEnvironment: Boolean,
    val hasParseMergeRequestUrl: Boolean,
    val hasParseGitRemoteUrl: Boolean,
    val hasDefaultApiUrl: Boolean,
    val hasAuthTokenHint: Boolean,
    val hasAliases: Boolean,
    val hasPreserveKeys: Boolean,
    val jsPreserveKeys: Set<String>,
    val jsDefaultPriority: Int,
    val jsIsDefaultEnabled: Boolean,
    val jsFormatId: String,
    val jsConfigFormatId: String,
    val jsEnabledByDefault: Boolean,
    val jsInterceptorPriority: Int,
    val jsProcessorPriority: Int,
    val jsTransformerPriority: Int,
    val jsEnricherPriority: Int,
    val jsRedactorPriority: Int,
    val hasGenerate: Boolean,
    val hasCompute: Boolean,
    val jsMinApiLevel: Int = KarandashApi.MIN_SUPPORTED_LEVEL,
    val jsDescriptorJson: String? = null,
    /** Every non-null member of the plugin object and its prototype chain (fields and functions). */
    val members: Set<String> = emptySet(),
)

/**
 * Holds eagerly-read JS plugin property values extracted during factory construction.
 */
@Serializable
private data class JsPropertyBag(
    val defaultPriority: Int,
    val isDefaultEnabled: Boolean,
    val formatId: String,
    val configFormatId: String,
    val enabledByDefault: Boolean,
    val interceptorPriority: Int,
    val processorPriority: Int,
    val transformerPriority: Int,
    val enricherPriority: Int,
    val redactorPriority: Int,
    val minApiLevel: Int,
)

/**
 * Factory that evaluates JS source, extracts plugin metadata, and produces
 * a fully-initialised [JsModuleBridge].
 *
 * Extracted from the [JsModuleBridge] companion to keep both files under the
 * 500-line LargeClass threshold.
 */
internal object JsModuleBridgeFactory {
    private const val DEFAULT_PRIORITY = 100
    private const val JS_TYPEOF = "typeof"

    /**
     * Evaluate JS source in the given [engine] and extract plugin metadata.
     *
     * @param engine An initialised QuickJs instance
     * @param source The JavaScript plugin source code
     * @param sourcePath Path to the source file (for error messages)
     * @return A fully-initialised [JsModuleBridge]
     * @throws IllegalArgumentException if the plugin is missing required fields
     */
    suspend fun fromSource(
        engine: QuickJs,
        source: String,
        sourcePath: String,
        guard: JsCallGuard = JsCallGuard(),
    ): JsModuleBridge {
        val transformedSource = source.replace(
            Regex("""^(\s*)export\s+default\s""", RegexOption.MULTILINE),
            "$1var __esm_default__ = ",
        )

        // QuickJS has no `console`; route it to the injected context logger so plugins written for Node load.
        val wrappedSource = """
            if (typeof globalThis.console === 'undefined') {
                globalThis.console = (function() {
                    function sink(name) {
                        return function() {
                            var f = globalThis[name];
                            if (typeof f === 'function') f(Array.prototype.map.call(arguments, String).join(' '));
                        };
                    }
                    var log = sink('__karandash_log'), warn = sink('__karandash_warn');
                    return { log: log, info: log, debug: log, warn: warn, error: warn };
                })();
            }
            var module = { exports: {} };
            var exports = module.exports;
            $transformedSource
            var plugin = (typeof __esm_default__ !== 'undefined' && __esm_default__ !== null && __esm_default__.id)
                ? __esm_default__
                : (typeof module.exports === 'object' && module.exports.id)
                    ? module.exports
                    : (typeof exports === 'object' && exports.id)
                        ? exports
                        : (typeof plugin !== 'undefined' ? plugin : {});
            undefined;
        """.trimIndent()

        engine.evaluate<Any?>(wrappedSource)

        val id = engine.evaluate<String>("String(plugin.id || '')").trim()
        require(id.isNotEmpty()) {
            "JS plugin at $sourcePath is missing required field: id"
        }

        val name = engine.evaluate<String>("String(plugin.name || '')").trim()
        require(name.isNotEmpty()) {
            "JS plugin at $sourcePath is missing required field: name"
        }

        val version = engine.evaluate<String>("String(plugin.version || '0.0.0')").trim()

        val typeStr = engine.evaluate<String>("String(plugin.type || 'RULE')").trim().uppercase()
        val moduleType = try {
            ModuleType.fromString(typeStr)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(
                "JS plugin at $sourcePath has invalid type: '$typeStr'. ${e.message}",
            )
        }

        val capabilities = buildAllCapabilities(engine)

        return JsModuleBridge.create(
            engine = engine,
            id = id,
            name = name,
            version = version,
            type = moduleType,
            capabilities = capabilities,
            guard = guard,
        )
    }

    /**
     * Single-pass construction of [JsModuleCapabilities]: reads each JS capability
     * check and maps it directly to the data class field so that each capability
     * key string appears exactly once (avoids StringLiteralDuplication).
     */
    private suspend fun buildAllCapabilities(engine: QuickJs): JsModuleCapabilities {
        val hasDefPri = evalBool(engine, isDefined("defaultPriority"))
        val hasDefEnabled = evalBool(engine, isDefined("isDefaultEnabled"))

        val props = readProperties(engine, hasDefPri, hasDefEnabled)
        val jsPreserveKeys = readPreserveKeys(engine)

        return JsModuleCapabilities(
            hasInitialize = evalBool(engine, isFunc("initialize")),
            hasShutdown = evalBool(engine, isFunc("shutdown")),
            hasValidate = evalBool(engine, isFunc("validate")),
            hasContributedRules = evalBool(engine, isFunc("contributedRules")),
            hasReport = evalBool(engine, isFunc("report")),
            hasReportEnriched = evalBool(engine, isFunc("reportEnriched")),
            hasFetchMergeRequest = evalBool(engine, isFunc("fetchMergeRequest")),
            hasFetchMergeRequestByUrl = evalBool(engine, isFunc("fetchMergeRequestByUrl")),
            hasAddLabels = evalBool(engine, isFunc("addLabels")),
            hasRemoveLabels = evalBool(engine, isFunc("removeLabels")),
            hasSetDraft = evalBool(engine, isFunc("setDraft")),
            hasUpdateTitle = evalBool(engine, isFunc("updateTitle")),
            hasUpdateDescription = evalBool(engine, isFunc("updateDescription")),
            hasApprove = evalBool(engine, isFunc("approve")),
            hasUnapprove = evalBool(engine, isFunc("unapprove")),
            hasPostComment = evalBool(engine, isFunc("postComment")),
            hasPostInlineComment = evalBool(engine, isFunc("postInlineComment")),
            hasUpdateComment = evalBool(engine, isFunc("updateComment")),
            hasFindExistingComment = evalBool(engine, isFunc("findExistingComment")),
            hasDeleteMarkedInlineComments = evalBool(engine, isFunc("deleteMarkedInlineComments")),
            hasResolveMarkedInlineComments = evalBool(engine, isFunc("resolveMarkedInlineComments")),
            hasFindAllMatchingComments = evalBool(engine, isFunc("findAllMatchingComments")),
            hasDeleteComment = evalBool(engine, isFunc("deleteComment")),
            hasRenderSuggestionBlock = evalBool(engine, isFunc("renderSuggestionBlock")),
            hasDefaultPriority = hasDefPri,
            hasIsDefaultEnabled = hasDefEnabled,
            hasDefaultConfig = evalBool(engine, isFunc("defaultConfig")),
            hasMissingAuthMessage = evalBool(engine, isFunc("missingAuthMessage")),
            hasCliCommands = evalBool(engine, isFunc("cliCommands")),
            hasConfigParse = evalBool(engine, isFunc("parseToMap")),
            hasConfigParseExtends = evalBool(engine, isFunc("parseExtendsOnly")),
            hasRuleConfigs = evalBool(engine, isFunc("ruleConfigs")),
            hasReporterConfigs = evalBool(engine, isFunc("reporterConfigs")),
            hasAiComplete = evalBool(engine, isFunc("complete")),
            hasAiIntercept = evalBool(engine, isFunc("intercept")),
            hasAiAnalyze = evalBool(engine, isFunc("analyze")),
            hasAiProcessPrompt = evalBool(engine, isFunc("processPrompt")),
            hasAiProcessSystemPrompt = evalBool(engine, isFunc("processSystemPrompt")),
            hasAiTransformResponse = evalBool(engine, isFunc("transformResponse")),
            hasAiEnrichContext = evalBool(engine, isFunc("enrichContext")),
            hasAiProvideBlock = evalBool(engine, isFunc("provideBlock")),
            hasAiRedact = evalBool(engine, isFunc("redact")),
            hasAiAllocateTokens = evalBool(engine, isFunc("allocate")),
            hasExecuteScripts = evalBool(engine, isFunc("executeScripts")),
            hasRuleExplanation = evalBool(engine, isFunc("ruleExplanation")),
            hasRuntimeReporterConfig = evalBool(engine, isFunc("runtimeReporterConfig")),
            hasConfigSchema = evalBool(engine, isFunc("configSchema")),
            hasDetectCiEnvironment = evalBool(engine, isFunc("detectCiEnvironment")),
            hasParseMergeRequestUrl = evalBool(engine, isFunc("parseMergeRequestUrl")),
            hasParseGitRemoteUrl = evalBool(engine, isFunc("parseGitRemoteUrl")),
            hasDefaultApiUrl = evalBool(engine, "${isFunc("defaultApiUrl")} || $JS_TYPEOF plugin.defaultApiUrl === 'string'"),
            hasAuthTokenHint = evalBool(engine, "${isFunc("authTokenHint")} || $JS_TYPEOF plugin.authTokenHint === 'string'"),
            hasAliases = evalBool(engine, "${isFunc("aliases")} || Array.isArray(plugin.aliases)"),
            hasPreserveKeys = evalBool(engine, "${isFunc("preserveKeys")} || Array.isArray(plugin.preserveKeys)"),
            hasGenerate = evalBool(engine, isFunc("generate")),
            hasCompute = evalBool(engine, isFunc("compute")),
            jsPreserveKeys = jsPreserveKeys,
            jsDefaultPriority = props.defaultPriority,
            jsIsDefaultEnabled = props.isDefaultEnabled,
            jsFormatId = props.formatId,
            jsConfigFormatId = props.configFormatId,
            jsEnabledByDefault = props.enabledByDefault,
            jsInterceptorPriority = props.interceptorPriority,
            jsProcessorPriority = props.processorPriority,
            jsTransformerPriority = props.transformerPriority,
            jsEnricherPriority = props.enricherPriority,
            jsRedactorPriority = props.redactorPriority,
            jsMinApiLevel = props.minApiLevel,
            jsDescriptorJson = readDescriptorJson(engine),
            members = readMembers(engine),
        )
    }

    private suspend fun readProperties(
        engine: QuickJs,
        hasDefaultPriority: Boolean,
        hasIsDefaultEnabled: Boolean,
    ): JsPropertyBag {
        val jsDefaultPriority = if (hasDefaultPriority) {
            readIntSafe(engine, "Number(plugin.defaultPriority)")
        } else {
            DEFAULT_PRIORITY
        }
        val jsIsDefaultEnabled = if (hasIsDefaultEnabled) {
            readBoolSafe(engine, "Boolean(plugin.isDefaultEnabled)")
        } else {
            false
        }
        val formatId = engine.evaluate<String>("String(plugin.formatId || '')").trim()
        val configFormatId = engine
            .evaluate<String>("String(plugin.configFormatId || plugin.formatId || '')")
            .trim()
        val enabledByDefault = readBoolSafe(engine, "Boolean(plugin.enabledByDefault)")
        val interceptorPriority = readIntSafe(engine, "Number(plugin.interceptorPriority || 100)")
        val processorPriority = readIntSafe(engine, "Number(plugin.processorPriority || 100)")
        val transformerPriority = readIntSafe(engine, "Number(plugin.transformerPriority || 100)")
        val enricherPriority = readIntSafe(engine, "Number(plugin.enricherPriority || 100)")
        val redactorPriority = readIntSafe(engine, "Number(plugin.redactorPriority || 100)")
        val minApiLevel = readIntSafe(
            engine,
            "Number(plugin.minApiLevel || ${KarandashApi.MIN_SUPPORTED_LEVEL})",
            KarandashApi.MIN_SUPPORTED_LEVEL,
        )
        return JsPropertyBag(
            defaultPriority = jsDefaultPriority,
            isDefaultEnabled = jsIsDefaultEnabled,
            formatId = formatId,
            configFormatId = configFormatId,
            enabledByDefault = enabledByDefault,
            interceptorPriority = interceptorPriority,
            processorPriority = processorPriority,
            transformerPriority = transformerPriority,
            enricherPriority = enricherPriority,
            redactorPriority = redactorPriority,
            minApiLevel = minApiLevel,
        )
    }

    private suspend fun readMembers(engine: QuickJs): Set<String> =
        JsTypeConverter
            .stringListFromJson(
                engine.evaluate<String>(
                    """
            (function() {
                var seen = {};
                for (var o = plugin; o && o !== Object.prototype; o = Object.getPrototypeOf(o)) {
                    Object.getOwnPropertyNames(o).forEach(function(k) {
                        if (k !== 'constructor' && plugin[k] !== undefined && plugin[k] !== null) seen[k] = true;
                    });
                }
                return JSON.stringify(Object.keys(seen));
            })()
                    """.trimIndent(),
                ),
            ).toSet()

    private suspend fun readPreserveKeys(engine: QuickJs): Set<String> =
        try {
            val json = engine.evaluate<String>(
                """
            (function() {
                var keys = plugin.preserveKeys;
                if (typeof keys === 'function') keys = keys();
                if (Array.isArray(keys)) return JSON.stringify(keys);
                return '[]';
            })()
                """.trimIndent(),
            )
            JsTypeConverter.stringListFromJson(json).toSet()
        } catch (ignored: Exception) {
            // boundary: evaluates guest JS
            ignored.rethrowCancellation()
            emptySet()
        }

    /**
     * `plugin.describe()` when defined, else the plugin's own data fields as descriptor properties
     * (a JS plugin declares SDK properties such as `supportedEnrichment` as plain fields).
     */
    private suspend fun readDescriptorJson(engine: QuickJs): String? =
        try {
            engine.evaluate<String>(
                """
            (function() {
                var d = typeof plugin.describe === 'function' ? plugin.describe() : null;
                if (d && typeof d === 'object') return JSON.stringify(d);
                var props = {};
                for (var k in plugin) { if (typeof plugin[k] !== 'function') props[k] = plugin[k]; }
                return JSON.stringify({ id: String(plugin.id), types: [String(plugin.type || 'RULE')], properties: props });
            })()
                """.trimIndent(),
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            // boundary: guest JS may throw from describe() or hold non-serializable fields;
            // null makes BridgeDescriptor log and fall back to SDK defaults
            e.rethrowCancellation()
            null
        }

    private suspend fun readBoolSafe(
        engine: QuickJs,
        expr: String,
    ): Boolean =
        try {
            engine.evaluate<Boolean>(expr)
        } catch (ignored: Exception) {
            // boundary: evaluates guest JS
            ignored.rethrowCancellation()
            false
        }

    private suspend fun readIntSafe(
        engine: QuickJs,
        expr: String,
        default: Int = DEFAULT_PRIORITY,
    ): Int =
        try {
            engine.evaluate<Int>(expr)
        } catch (ignored: Exception) {
            // boundary: evaluates guest JS
            ignored.rethrowCancellation()
            default
        }

    private suspend fun evalBool(
        engine: QuickJs,
        expr: String
    ): Boolean = engine.evaluate<Boolean>(expr)

    private fun isFunc(name: String): String = "$JS_TYPEOF plugin.$name === 'function'"

    private fun isDefined(name: String): String = "$JS_TYPEOF plugin.$name !== 'undefined'"
}
