package io.karandash.loader.bridge

import io.karandash.api.PluginContext
import io.karandash.api.context.EnrichmentContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Initializes one composite guest for all of its parts.
 *
 * Every part of a composite registration ([asRegisteredModules]) forwards `initialize` to the same guest, each
 * with the config of its own type. Passed through as-is, the guest kept only the config of whichever part initialized
 * last. Here each call hands the guest the union of every part config seen since the last [reset] (on a clashing key
 * the calling part wins), and a call that would repeat the config and enrichment the guest was last initialized with
 * returns that result instead of initializing it again, so a guest whose parts share one config block is
 * initialized once. A single-type guest ([composite] false) is passed through untouched.
 */
internal class SharedGuestInit(
    private val composite: Boolean,
) {
    /** Not @Serializable: runtime state, holds a live [EnrichmentContext]. */
    private data class Applied(
        val config: JsonObject,
        val enrichment: EnrichmentContext,
        val result: Boolean,
    )

    private val mutex = Mutex()
    private val merged = MutableStateFlow(JsonObject(emptyMap()))
    private val applied = MutableStateFlow<Applied?>(null)

    suspend fun initialize(
        context: PluginContext,
        init: suspend (PluginContext) -> Boolean,
    ): Boolean {
        if (!composite) return init(context)
        return mutex.withLock {
            val own = context.pluginConfig as? JsonObject ?: JsonObject(emptyMap())
            val config = JsonObject(merged.value + own)
            merged.value = config
            val last = applied.value
            if (last != null && last.config == config && last.enrichment === context.enrichment) {
                last.result
            } else {
                init(context.withConfig(config)).also { applied.value = Applied(config, context.enrichment, it) }
            }
        }
    }

    /** Forgets the merged config; the guest's next `initialize` starts from its calling part's config alone. */
    suspend fun reset() {
        if (!composite) return
        mutex.withLock {
            merged.value = JsonObject(emptyMap())
            applied.value = null
        }
    }

    private fun PluginContext.withConfig(config: JsonElement): PluginContext =
        object : PluginContext by this {
            override val pluginConfig: JsonElement = config
        }
}
