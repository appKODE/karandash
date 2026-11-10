package io.karandash.manifest

import kotlinx.serialization.Serializable

/**
 * Data model for `.karandash/manifest.yaml`.
 *
 * Controls which plugins are installed and how missing plugins are handled.
 * Built-in modules (rule validators, providers, reporters) do NOT need to be
 * declared here - they are always loaded unconditionally via [io.karandash.bootstrap.BuiltInPluginLoader].
 *
 * @property pluginDeclarations Plugins to install. `version: null` means latest at install time.
 * @property registryUrl Override URL for the plugin registry.
 * @property pluginDir Directory where plugins are installed.
 * @property onMissing How to handle config keys referencing unloaded modules. Defaults to [MissingPluginBehavior.WARN].
 * @property registryToken Authentication token for the plugin registry.
 * @property registryAuthHeader HTTP header name to use for [registryToken]. Defaults to "Authorization" (Bearer).
 * @property allowLocalhost `plugins.allow-localhost`: registry index and plugin downloads may reach loopback/private hosts.
 */
@Serializable
data class ManifestData(
    val pluginDeclarations: List<PluginDeclaration> = emptyList(),
    val registryUrl: String? = null,
    val pluginDir: String? = null,
    val onMissing: MissingPluginBehavior = MissingPluginBehavior.WARN,
    val registryToken: String? = null,
    val registryAuthHeader: String? = null,
    val allowLocalhost: Boolean = false,
) {
    /** Masks [registryToken] so a logged manifest never prints it. */
    override fun toString(): String =
        "ManifestData(pluginDeclarations=$pluginDeclarations, registryUrl=$registryUrl, pluginDir=$pluginDir, " +
            "onMissing=$onMissing, registryToken=${registryToken?.let { "***" }}, " +
            "registryAuthHeader=$registryAuthHeader, allowLocalhost=$allowLocalhost)"
}

/**
 * A single plugin declaration in `manifest.yaml`.
 *
 * @property id Plugin module ID (e.g. `"com.example.my-rule"`).
 * @property version Pinned version string, or `null` meaning "latest at install time".
 *   The validation pipeline ignores this field - it is only used by `karandash install`.
 * @property source Optional direct download URL override (bypasses registry lookup).
 */
@Serializable
data class PluginDeclaration(
    val id: String,
    val version: String? = null,
    val source: String? = null,
)
