package io.karandash.loader

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.KarandashApi
import io.karandash.api.Plugin

/**
 * Versions of the native/WASM plugin C ABI (`karandash_abi_version`) this host can load.
 */
object NativeAbi {
    /**
     * Inclusive range of supported `karandash_abi_version` values. v2 adds the optional `karandash_set_host` export
     * (the host-service channel); a v1 guest loads as before and its host services answer `unsupported`.
     */
    val SUPPORTED: IntRange = 1..2

    /** The host-channel ABI passed to `karandash_set_host`; a guest refuses (returns 0) one it does not speak. */
    const val HOST_ABI: Int = 2

    /** The optional v2 export that hands the guest the host-service channel. */
    const val SET_HOST: String = "karandash_set_host"

    /** `true` when a plugin reporting [version] can be loaded by this host. */
    fun isSupported(version: Int): Boolean = version in SUPPORTED

    /** User-facing rejection message for an unsupported [version]. */
    fun unsupportedMessage(version: Int): String = "Unsupported ABI version: $version (supported ${SUPPORTED.first}..${SUPPORTED.last})"
}

/**
 * Returns a user-facing reason when [plugin] explicitly requires a newer plugin API level than
 * this host provides ([KarandashApi.LEVEL]), or `null` when it can be loaded.
 *
 * Plugins that do not declare [Plugin.minApiLevel] get the default (the oldest supported level)
 * and always pass.
 */
@OptIn(ExperimentalKarandashApi::class)
internal fun apiLevelIncompatibility(plugin: Plugin): String? = apiLevelIncompatibility(plugin.id, plugin.minApiLevel)

/** Same as [apiLevelIncompatibility] for a plugin known only by [pluginId] and its declared [minApiLevel]. */
internal fun apiLevelIncompatibility(
    pluginId: String,
    minApiLevel: Int,
): String? =
    if (minApiLevel > KarandashApi.LEVEL) {
        "Plugin '$pluginId' requires karandash plugin API level $minApiLevel, " +
            "but this karandash provides level ${KarandashApi.LEVEL}. Upgrade karandash."
    } else {
        null
    }
