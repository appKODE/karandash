package io.karandash.loader.bridge.transport

import com.dokar.quickjs.QuickJs
import io.karandash.api.PluginContext
import io.karandash.api.support.rethrowCancellation
import io.karandash.api.support.suspendCatching
import io.karandash.loader.bridge.BridgeAbi
import io.karandash.loader.bridge.BridgeConstants
import io.karandash.loader.bridge.BridgeOp
import io.karandash.loader.bridge.BridgeTransport
import io.karandash.loader.bridge.CType
import io.karandash.loader.bridge.host.HostServiceDispatcher
import io.karandash.loader.js.JsCallGuard
import io.karandash.loader.js.JsModuleCapabilities
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonPrimitive

/**
 * [BridgeTransport] implementation for JavaScript plugins running in QuickJS.
 *
 * Every op resolves through [BridgeAbi] to its [BridgeOp.js] member on the plugin object. A member
 * may be a function (called with the arguments) or a plain field (returned as-is); results come back
 * as JSON strings.
 */
internal class JsTransport(
    private val engine: QuickJs,
    private val capabilities: JsModuleCapabilities,
    private val moduleId: String,
    private val logger: Logger = NoOpLogger,
    private val guard: JsCallGuard = JsCallGuard(),
) : BridgeTransport {
    internal val storedContext = MutableStateFlow<PluginContext?>(null)

    override val host: HostServiceDispatcher? get() = guard.host

    override suspend fun call(
        method: String,
        vararg args: String
    ): String? = invoke(method, args).let { if (returnsBool(method)) it?.let(::isTrue)?.toString() else it }

    /** A [CType.BOOL] op that answered anything but `true`/`1` reported failure, as on the C ABI transports. */
    override suspend fun callVoid(
        method: String,
        vararg args: String
    ) {
        val raw = invoke(method, args)
        if (returnsBool(method) && raw != null && !isTrue(raw)) {
            logger.warn("[JS:$moduleId] $method reported failure (result $raw)")
        }
    }

    /** Reads a plugin member (a field, or a zero-argument function) as a string; JSON for non-string values. */
    override fun getProperty(name: String): String {
        if (name == BridgeConstants.PROP_ID) return moduleId
        val code = "(function() { var f = plugin['${jsMember(name)}']; " +
            "var v = typeof f === 'function' ? f.call(plugin) : f; " +
            "return (v === undefined || v === null) ? '' : (typeof v === 'string' ? v : JSON.stringify(v)); })()"
        return suspendCatching {
            // bridge-boundary: BridgeTransport.getProperty is synchronous
            kotlinx.coroutines.runBlocking { guard.run(name) { engine.evaluate<String>(code) } }
        }.getOrDefault("")
    }

    override fun getIntProperty(
        name: String,
        default: Int
    ): Int =
        when (name) {
            "defaultPriority" -> capabilities.jsDefaultPriority
            "interceptorPriority" -> capabilities.jsInterceptorPriority
            "processorPriority" -> capabilities.jsProcessorPriority
            "transformerPriority" -> capabilities.jsTransformerPriority
            "enricherPriority" -> capabilities.jsEnricherPriority
            "redactorPriority" -> capabilities.jsRedactorPriority
            BridgeConstants.PROP_MIN_API_LEVEL -> capabilities.jsMinApiLevel
            else -> getProperty(name).toIntOrNull() ?: default
        }

    override fun getBoolProperty(
        name: String,
        default: Boolean
    ): Boolean =
        when (name) {
            "isDefaultEnabled" -> capabilities.jsIsDefaultEnabled
            "enabledByDefault" -> capabilities.jsEnabledByDefault
            else -> getProperty(name).toBooleanStrictOrNull() ?: default
        }

    /** True when the plugin object (or its prototype chain) defines the op's JS member. */
    override fun hasCapability(name: String): Boolean = jsMember(name) in capabilities.members

    override fun logError(
        method: String,
        exception: Exception
    ) {
        logger.warn("[JS:$moduleId] $method failed: ${exception.message}")
    }

    override fun logInfo(
        method: String,
        message: String
    ) {
        logger.info("[JS:$moduleId] $message")
    }

    private suspend fun invoke(
        method: String,
        args: Array<out String>,
    ): String? {
        val argsStr = args.withIndex().joinToString("") { (i, arg) -> ", " + toJsExpression(method, i, arg) }
        val code = "(function() { var f = plugin['${jsMember(method)}']; " +
            "var result = typeof f === 'function' ? f.call(plugin$argsStr) : f; " +
            "return (result === undefined || result === null) ? null : JSON.stringify(result); })()"
        return try {
            val result = guard.run(method) { engine.evaluate<String>(code) }
            val trimmed = result.trim()
            if (trimmed == BridgeConstants.NULL_SENTINEL || trimmed == "undefined") {
                null
            } else {
                decodeResult(trimmed)
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            // boundary: JS runtime
            e.rethrowCancellation()
            logError(method, e)
            null
        }
    }

    private fun returnsBool(method: String): Boolean = BridgeAbi.find(method)?.returns == CType.BOOL

    /** JS member name for a logical method: [BridgeOp.js] for table ops, the name itself otherwise. */
    private fun jsMember(method: String): String = BridgeAbi.find(method)?.js ?: method

    private companion object {
        private fun isTrue(raw: String): Boolean = raw == "true" || raw == "1"

        /** Parses a JSON argument inside the shim; a value that is not JSON stays the plain string. */
        private const val PARSE_JSON = "(function(s) { try { return JSON.parse(s); } catch (e) { return s; } })"

        /** A string result comes back JSON-encoded (`JSON.stringify`); decode it, return other JSON values as-is. */
        private fun decodeResult(value: String): String =
            if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
                (KarandashJson.Default.parseToJsonElement(value) as JsonPrimitive).content
            } else {
                value
            }

        /**
         * JS expression for argument [index] of [method]. Every argument travels as a JSON string literal, never as
         * code: a parameter that [BridgeAbi.params] marks as JSON (`*_json`) or `int32_t` (`#`) is parsed with
         * `JSON.parse` inside the shim; the bare `null` sentinel (an absent optional value) is passed as JS `null`.
         */
        private fun toJsExpression(
            method: String,
            index: Int,
            arg: String,
        ): String {
            if (arg == BridgeConstants.NULL_SENTINEL) return "null"
            val literal = JsonPrimitive(arg).toString()
            val param = BridgeAbi
                .find(method)
                ?.params
                ?.getOrNull(index)
                .orEmpty()
            return if (param.endsWith("_json") || param.startsWith("#")) "$PARSE_JSON($literal)" else literal
        }
    }
}
