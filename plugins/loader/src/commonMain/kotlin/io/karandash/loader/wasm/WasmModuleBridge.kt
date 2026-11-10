package io.karandash.loader.wasm

import io.karandash.api.KarandashApi
import io.karandash.api.Module
import io.karandash.api.ModuleDescriptor
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.support.rethrowCancellation
import io.karandash.api.support.suspendCatching
import io.karandash.loader.bridge.BridgeContextSerializer
import io.karandash.loader.bridge.BridgeDescriptor
import io.karandash.loader.bridge.DelegatingModuleBridge
import io.karandash.loader.bridge.PartAware
import io.karandash.loader.bridge.SharedGuestInit
import io.karandash.loader.bridge.host.HostServiceDispatcher
import io.karandash.loader.bridge.transport.WasmTransport
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.support.KarandashJson
import io.karandash.wasm.WasmEngine
import io.karandash.wasm.WasmMemoryProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Bridges a WASM module to the Karandash Module interface.
 *
 * Uses [WasmEngine] to call WASM exports, passing data as
 * length-prefixed UTF-8 JSON via [WasmMemoryProtocol].
 *
 * Per-type methods are dispatched by [DelegatingModuleBridge] over [WasmTransport].
 * Operations that require WASM-specific semantics (null-throwing providers, AI continuation)
 * delegate to [WasmBridgeOperations].
 */
@OptIn(io.karandash.api.ExperimentalKarandashApi::class)
internal class WasmModuleBridge(
    initialEngine: WasmEngine,
    private val logger: io.karandash.sdk.Logger = io.karandash.sdk.NoOpLogger,
    /** The guest's export names ([WasmExportNames]); null when unknown, then no ABI op counts as exported. */
    private val exportNames: Set<String>? = null,
    /**
     * Parameter count per export ([WasmExportNames.paramCounts]). An op whose export takes the C ABI shape
     * (each string as `ptr, len`) goes through [transport]; otherwise the single-JSON shape.
     */
    private val exportParams: Map<String, Int> = emptyMap(),
    /**
     * The channel the engine's `env.karandash_host_call` import calls (the loader builds both); without one the
     * bridge keeps its own, serving the AI continuation loop from the context of [initialize].
     */
    host: HostServiceDispatcher? = null,
    /** Builds a fresh engine when [initialize] follows [shutdown]; without it the bridge cannot re-initialize. */
    private val engineFactory: (() -> WasmEngine)? = null,
) : DelegatingModuleBridge(),
    PartAware {
    // null once shutdown() closed the engine; initialize() recreates it via engineFactory.
    // Declared first: the metadata initializers below read through it.
    private val engineRef = MutableStateFlow<WasmEngine?>(initialEngine)
    private val engine: WasmEngine
        get() = checkNotNull(engineRef.value) { "WASM module used after shutdown() without re-initialize()" }

    // Eagerly-read metadata (before transport is fully available)
    override val id: String = readString(WasmAbi.MODULE_ID)
    override val name: String = readString(WasmAbi.MODULE_NAME)
    override val version: String = readString(WasmAbi.MODULE_VERSION)
    private val typeStr: String = readString(WasmAbi.MODULE_TYPE)
    override val type: ModuleType = parseModuleType(typeStr)
    override val descriptor: BridgeDescriptor = BridgeDescriptor.parse(readString(WasmAbi.DESCRIBE), id, logger)

    /** The context the guest was initialized with: host services run on it outside `analyze`. */
    internal val initContext = MutableStateFlow<PluginContext?>(null)

    private val hostChannel: HostServiceDispatcher = host ?: HostServiceDispatcher({ id }, logger, { initContext.value })

    override val transport = WasmTransport({ engine }, id, logger, exportNames, hostChannel)

    // --- Eagerly-read properties ---

    override fun preserveKeys(): Set<String> {
        val json = readString(WasmAbi.PRESERVE_KEYS)
        if (json.isBlank() || json == "[]") return emptySet()
        return suspendCatching {
            KarandashJson.Default.decodeFromString<List<String>>(json).toSet()
        }.getOrDefault(emptySet())
    }

    override val defaultPriority: Int = readIntSafe(WasmAbi.DEFAULT_PRIORITY)

    @OptIn(io.karandash.api.ExperimentalKarandashApi::class)
    override val minApiLevel: Int = readIntSafe(WasmAbi.MIN_API_LEVEL, KarandashApi.MIN_SUPPORTED_LEVEL)

    private val enabledByDefault: Boolean = descriptor.bool(ModuleDescriptor.ENABLED_BY_DEFAULT) ?: false

    override fun defaultEnabledFor(type: ModuleType): Boolean =
        descriptor.defaultEnabled(
            type = type,
            enabledByDefault = enabledByDefault,
        ) { readIntSafe(WasmAbi.IS_DEFAULT_ENABLED, 1) == 1 }

    override val defaultEnabled: Boolean = defaultEnabledFor(type)

    // --- Module lifecycle ---

    private val sharedInit = SharedGuestInit(composite = descriptor.types.distinct().size > 1)

    override suspend fun initialize(context: PluginContext): Boolean = sharedInit.initialize(context, ::initializeGuest)

    private suspend fun initializeGuest(context: PluginContext): Boolean {
        if (engineRef.value == null && engineFactory == null) {
            logger.warn("[WASM:$id] cannot re-initialize after shutdown(): no engine factory")
            return false
        }
        return try {
            if (engineRef.value == null) engineRef.value = engineFactory?.invoke()
            initContext.value = context
            if (exportNames?.contains(WasmAbi.INITIALIZE) == false) return true
            transport.callI32(WasmAbi.INITIALIZE, listOf(BridgeContextSerializer.serialize(context))) == 1
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            // boundary: WASM runtime
            e.rethrowCancellation()
            logVerbose(LOG_TAG, e)
            false
        }
    }

    override suspend fun shutdown() {
        sharedInit.reset()
        val engine = engineRef.getAndUpdate { null } ?: return
        try {
            engine.callI32(WasmAbi.SHUTDOWN)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            // boundary: WASM runtime -- best-effort shutdown
            e.rethrowCancellation()
            logVerbose(LOG_TAG, e)
        }
        engine.close()
    }

    override val dslName: String get() = descriptor.string(ModuleDescriptor.DSL_NAME) ?: id

    override val hoverDocumentation: String? get() = descriptor.string(ModuleDescriptor.HOVER_DOCUMENTATION)

    override val supportedEnrichment: List<String> =
        descriptor.stringList(ModuleDescriptor.SUPPORTED_ENRICHMENT).orEmpty()

    override val requiredEnrichment: List<String> =
        descriptor.stringList(ModuleDescriptor.REQUIRED_ENRICHMENT).orEmpty()

    override val formatId: String get() = readString(WasmAbi.FORMAT_ID)

    /** The CONFIG_FORMAT part reports `karandash_config_format_id`, falling back to the one `formatId` export. */
    override fun formatIdFor(type: ModuleType): String =
        if (type == ModuleType.CONFIG_FORMAT) transport.getProperty("configFormatId").ifEmpty { formatId } else formatId

    // --- WASM-specific dispatch ---
    // fetchMergeRequest/postComment pick the call shape by export arity: the C ABI shape goes through the transport
    // like every other op, a single-JSON export through WasmBridgeOperations. analyze() runs the AI
    // continuation loop.

    private fun abiShape(
        export: String,
        strings: Int,
    ): Boolean = exportParams[export] == strings * 2

    override suspend fun fetchMergeRequest(
        projectId: String,
        mrId: String,
    ): MergeRequest =
        if (abiShape(WasmAbi.FETCH_MERGE_REQUEST, strings = 2)) {
            super.fetchMergeRequest(projectId, mrId)
        } else {
            WasmBridgeOperations.fetchMergeRequest(transport, id, projectId, mrId)
        }

    override suspend fun postComment(
        projectId: String,
        mrId: String,
        body: String,
    ): String =
        if (abiShape(WasmAbi.POST_COMMENT, strings = 3)) {
            super.postComment(projectId, mrId, body)
        } else {
            WasmBridgeOperations.postComment(transport, projectId, mrId, body)
        }

    override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
        if (!transport.hasCapability("aiAnalyze")) return emptyList()
        return try {
            WasmBridgeOperations.analyze(transport, id, logger, context, hostChannel)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            // boundary: WASM runtime
            e.rethrowCancellation()
            logVerbose(LOG_TAG, e)
            emptyList()
        }
    }

    // --- Private helpers ---

    private fun readString(funcName: String): String =
        try {
            val ptr = engine.callI32(funcName)
            if (ptr == 0) "" else WasmMemoryProtocol.readLengthPrefixedString(engine, ptr)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            // boundary: WASM runtime
            logVerbose(LOG_TAG, e)
            ""
        }

    private fun readIntSafe(
        funcName: String,
        default: Int = DEFAULT_PRIORITY,
    ): Int =
        try {
            engine.callI32(funcName)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            // boundary: WASM runtime
            logVerbose(LOG_TAG, e)
            default
        }

    private fun parseModuleType(rawType: String): ModuleType =
        io.karandash.loader.BridgeUtils
            .parseModuleType(rawType, "WASM")

    private fun logVerbose(
        method: String,
        e: Throwable,
    ) {
        logger.debug("[WASM:$id] $method failed: ${e.message}")
    }

    private companion object {
        private const val LOG_TAG = "bridge"
        private const val DEFAULT_PRIORITY = 100
    }
}
