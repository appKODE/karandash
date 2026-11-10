package io.karandash.loader.js

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import io.karandash.api.PluginContext
import io.karandash.loader.bridge.GuestEnv
import io.karandash.loader.bridge.host.HostServiceDispatcher
import kotlinx.coroutines.runBlocking

/**
 * Injects PluginContext SDK methods as global functions in a QuickJS engine. Each binding reads [currentContext] when
 * the guest calls it, so a guest sees the context of its latest `initialize`, not the one it was loaded with.
 *
 * With a [host], `__karandash_host_call(service, requestJson)` serves the host-service channel (see
 * [HostServiceDispatcher]) synchronously, like the cache bindings; the `ctx.*` shims are built on it.
 */
class JsContextInjector(
    private val engine: QuickJs,
    private val currentContext: () -> PluginContext,
    private val host: HostServiceDispatcher? = null,
) {
    constructor(engine: QuickJs, context: PluginContext) : this(engine, { context })

    private val context: PluginContext get() = currentContext()

    /**
     * Injects SDK bindings into the QuickJS engine.
     * Must be called before evaluating plugin code.
     */
    fun inject() {
        injectLogging()
        injectEnvironment()
        injectPatterns()
        injectCache()
        injectHostCall()
    }

    private fun injectHostCall() {
        val dispatcher = host ?: return
        engine.function<String>("__karandash_host_call") { args ->
            dispatcher.call(args[0] as String, args.getOrNull(1) as? String ?: "{}")
        }
    }

    /** `__karandash_resolve_pattern(p)` returns the regex source the host compiles for any pattern tier. */
    private fun injectPatterns() {
        engine.function<String, String>("__karandash_resolve_pattern") { pattern ->
            context.resolvePattern(pattern).pattern
        }
    }

    private fun injectLogging() {
        engine.function<String, Unit>("__karandash_log") { message ->
            context.log(message)
        }
        engine.function<String, Unit>("__karandash_warn") { message ->
            context.warn(message)
        }
    }

    private fun injectEnvironment() {
        engine.function<String, String?>("__karandash_env") { name ->
            if (GuestEnv.allows(name)) context.env(name) else null
        }
    }

    private fun injectCache() {
        engine.function<String?>("__karandash_cache_get") { args ->
            val cacheName = args[0] as String
            val key = args[1] as String
            val cache = context.cacheProvider.getStringCache(cacheName)
            // bridge-boundary: JS engine does not support suspend functions
            runBlocking { cache.get(key) }
        }
        engine.function<Unit>("__karandash_cache_put") { args ->
            val cacheName = args[0] as String
            val key = args[1] as String
            val value = args[2] as String
            val cache = context.cacheProvider.getStringCache(cacheName)
            // bridge-boundary: JS engine does not support suspend functions
            runBlocking { cache.put(key, value) }
        }
    }
}
