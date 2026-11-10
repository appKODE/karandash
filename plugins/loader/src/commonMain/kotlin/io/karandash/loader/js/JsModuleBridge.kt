package io.karandash.loader.js

import com.dokar.quickjs.QuickJs
import io.karandash.api.Module
import io.karandash.api.ModuleDescriptor
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.support.rethrowCancellation
import io.karandash.loader.bridge.BridgeContextSerializer
import io.karandash.loader.bridge.BridgeDescriptor
import io.karandash.loader.bridge.DelegatingModuleBridge
import io.karandash.loader.bridge.PartAware
import io.karandash.loader.bridge.SharedGuestInit
import io.karandash.loader.bridge.transport.JsTransport

/**
 * Bridges a JavaScript plugin running in QuickJS to the Karandash Module interface.
 * Translates between Kotlin objects and JS JSON strings via [JsTypeConverter].
 *
 * Per-type methods are dispatched by [DelegatingModuleBridge] over [JsTransport];
 * this class holds the engine, lifecycle and capability-backed metadata.
 *
 * Created via [JsModuleBridgeFactory.fromSource] which evaluates the source and
 * reads metadata.
 */
@OptIn(io.karandash.api.ExperimentalKarandashApi::class)
class JsModuleBridge private constructor(
    private val engine: QuickJs,
    override val id: String,
    override val name: String,
    override val version: String,
    override val type: ModuleType,
    private val capabilities: JsModuleCapabilities,
    private val logger: io.karandash.sdk.Logger = io.karandash.sdk.NoOpLogger,
    internal val guard: JsCallGuard = JsCallGuard(),
) : DelegatingModuleBridge(),
    PartAware {
    override val descriptor: BridgeDescriptor = BridgeDescriptor.parse(capabilities.jsDescriptorJson, id, logger)
    override val transport = JsTransport(engine, capabilities, id, logger, guard)

    companion object {
        /**
         * Evaluate JS source in the given [engine] and extract plugin metadata.
         * Delegates to [JsModuleBridgeFactory.fromSource].
         */
        suspend fun fromSource(
            engine: QuickJs,
            source: String,
            sourcePath: String,
        ): JsModuleBridge = JsModuleBridgeFactory.fromSource(engine, source, sourcePath)

        /** As [fromSource], with every later call into the engine bounded by [guard]. */
        internal suspend fun fromSource(
            engine: QuickJs,
            source: String,
            sourcePath: String,
            guard: JsCallGuard,
        ): JsModuleBridge = JsModuleBridgeFactory.fromSource(engine, source, sourcePath, guard = guard)

        /** Internal factory used by [JsModuleBridgeFactory] to construct instances. */
        internal fun create(
            engine: QuickJs,
            id: String,
            name: String,
            version: String,
            type: ModuleType,
            capabilities: JsModuleCapabilities,
            guard: JsCallGuard = JsCallGuard(),
        ): JsModuleBridge =
            JsModuleBridge(
                engine = engine,
                id = id,
                name = name,
                version = version,
                type = type,
                capabilities = capabilities,
                guard = guard,
            )
    }

    // --- Module lifecycle ---

    private val sharedInit = SharedGuestInit(composite = descriptor.types.distinct().size > 1)

    override suspend fun initialize(context: PluginContext): Boolean = sharedInit.initialize(context, ::initializeGuest)

    private suspend fun initializeGuest(context: PluginContext): Boolean {
        transport.storedContext.value = context
        if (!capabilities.hasInitialize) return true
        return try {
            val contextJson = buildContextJson(context)
            val result = guard.run("initialize") { engine.evaluate<String>("JSON.stringify(plugin.initialize($contextJson))") }
            result.trim('"').toBoolean()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            // boundary: JS runtime
            e.rethrowCancellation()
            transport.storedContext.value?.warn("[JS:$id] initialize failed: ${e.message}")
            false
        }
    }

    override suspend fun shutdown() {
        sharedInit.reset()
        if (!capabilities.hasShutdown) return
        try {
            guard.run("shutdown") { engine.evaluate<Any?>("plugin.shutdown()") }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            // boundary: JS runtime -- best-effort shutdown
            e.rethrowCancellation()
            logger.warn("[JS:$id] shutdown failed: ${e.message}")
        }
    }

    // --- Eagerly-read properties (from capabilities, not transport) ---

    override fun preserveKeys(): Set<String> = capabilities.jsPreserveKeys

    override val defaultPriority: Int get() = capabilities.jsDefaultPriority
    private val enabledByDefault: Boolean =
        descriptor.bool(ModuleDescriptor.ENABLED_BY_DEFAULT) ?: capabilities.jsEnabledByDefault

    override fun defaultEnabledFor(type: ModuleType): Boolean =
        descriptor.defaultEnabled(
            type = type,
            enabledByDefault = enabledByDefault,
        ) { !capabilities.hasIsDefaultEnabled || capabilities.jsIsDefaultEnabled }

    override val defaultEnabled: Boolean = defaultEnabledFor(type)

    @OptIn(io.karandash.api.ExperimentalKarandashApi::class)
    override val minApiLevel: Int get() = capabilities.jsMinApiLevel
    override val formatId: String get() = formatIdFor(type)

    override fun formatIdFor(type: ModuleType): String =
        if (type == ModuleType.CONFIG_FORMAT) capabilities.jsConfigFormatId else capabilities.jsFormatId

    override val dslName: String get() = descriptor.string(ModuleDescriptor.DSL_NAME) ?: id

    override val hoverDocumentation: String? get() = descriptor.string(ModuleDescriptor.HOVER_DOCUMENTATION)

    override val supportedEnrichment: List<String> =
        descriptor.stringList(ModuleDescriptor.SUPPORTED_ENRICHMENT).orEmpty()

    override val requiredEnrichment: List<String> =
        descriptor.stringList(ModuleDescriptor.REQUIRED_ENRICHMENT).orEmpty()

    /**
     * Build a JavaScript object expression representing the [PluginContext].
     *
     * The returned string is a JS expression (not pure JSON) that includes
     * the SDK methods `log` (also `log.info/warn/error`), `warn`, `env`, `resolvePattern`, `cache`, `content.resolve`
     * `ai.complete` and `skip`, over the global functions injected by [JsContextInjector]. With the host channel
     * (`__karandash_host_call`) `cache`, `log.*`, `content` and `ai` go through it; `content.resolve` and
     * `ai.complete` return the service's reply object (`{err}` on failure), see `tools/examples/karandash-plugin.d.ts`.
     */
    private fun buildContextJson(context: PluginContext): String {
        val jsonObject = BridgeContextSerializer.toJsonObject(context)
        return """Object.assign($jsonObject, (function() {
            var hasHost = typeof __karandash_host_call === 'function';
            function hc(service, request) {
                if (!hasHost) return { err: 'unsupported' };
                return JSON.parse(__karandash_host_call(service, JSON.stringify(request || {})));
            }
            function log(msg) { if (typeof __karandash_log === 'function') __karandash_log(msg); }
            function leveled(level) { return function(msg) { hc('log', { level: level, message: String(msg) }); }; }
            log.info = leveled('info'); log.warn = leveled('warn'); log.error = leveled('error');
            return {
                log: log,
                warn: function(msg) { if (typeof __karandash_warn === 'function') __karandash_warn(msg); },
                env: function(name) { if (typeof __karandash_env === 'function') return __karandash_env(name); return null; },
                resolvePattern: function(p) { if (typeof __karandash_resolve_pattern === 'function') return __karandash_resolve_pattern(p); return null; },
                cache: {
                    get: function(cacheName, key) {
                        if (hasHost) { var r = hc('cache.get', { cache: cacheName, key: key }); return r.value === undefined ? null : r.value; }
                        if (typeof __karandash_cache_get === 'function') return __karandash_cache_get(cacheName, key);
                        return null;
                    },
                    put: function(cacheName, key, value) {
                        if (hasHost) { hc('cache.put', { cache: cacheName, key: key, value: String(value) }); return; }
                        if (typeof __karandash_cache_put === 'function') __karandash_cache_put(cacheName, key, value);
                    }
                },
                content: { resolve: function(source) { return hc('resolveContent', { source: source }); } },
                ai: { complete: function(request) { return hc('ai.complete', typeof request === 'string' ? { prompt: request } : request); } },
                skip: function(reason) { return hc('skip', { reason: String(reason) }); }
            };
        })())"""
    }
}
