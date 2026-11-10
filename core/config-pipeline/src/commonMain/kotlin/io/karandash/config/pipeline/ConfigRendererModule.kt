package io.karandash.config.pipeline

import kotlinx.serialization.json.JsonObject

/**
 * Renders configuration output in a specific format.
 *
 * Parallel to [io.karandash.api.module.ConfigFormatModule] (parsing), this interface
 * handles config **output generation** for init, wizard, and template commands.
 *
 * Built-in implementations (yaml, json, kts) are registered alongside their
 * format modules in [ConfigFormatRegistry]. Community plugins can register
 * additional renderers for custom formats.
 *
 * @property formatId Unique format identifier matching [io.karandash.api.module.ConfigFormatModule.formatId].
 * @property defaultOutputPath Default output file path (e.g. ".karandash/config.yaml").
 */
interface ConfigRendererModule {
    val formatId: String
    val defaultOutputPath: String

    /**
     * Render a full configuration object to a string in this format.
     *
     * @param config Configuration object to render.
     * @return Rendered config content.
     */
    fun renderConfig(config: JsonObject): String

    /**
     * Render a minimal profile-only configuration stub.
     *
     * @param profileId The profile identifier to reference.
     * @return Rendered config content with just a profile reference.
     */
    fun renderProfileConfig(profileId: String): String

    /**
     * Render a wizard-generated configuration with profile, provider, and token.
     *
     * @param profileId The selected profile identifier.
     * @param platform The selected platform provider id (e.g. "github").
     * @param tokenVar The environment variable name for the platform token.
     * @return Rendered config content.
     */
    fun renderWizardConfig(
        profileId: String,
        platform: String,
        tokenVar: String
    ): String
}
