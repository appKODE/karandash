package io.karandash.config.pipeline

import io.karandash.api.errors.KarandashError
import io.karandash.config.schema.ConfigSections
import io.karandash.normalize.ConfigKeyNormalizer
import io.karandash.utils.SuggestionEngine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Edit distance within which an unknown config key or module id gets a did-you-mean hint. */
internal const val MODULE_ID_MAX_DISTANCE = 2

/**
 * Finds top-level config keys no part of Karandash reads ([KarandashError.UNKNOWN_YAML_CONFIG_KEY]),
 * with a "did you mean" hint. Warn-only: an unknown key never fails the run.
 */
object UnknownTopLevelKeys {
    /**
     * Top-level keys read outside [ConfigSections.all] (`severity-cap` by its pipeline extension).
     * Kebab-case; matched after camelCase normalization.
     */
    private val EXTRA_KEYS: Set<String> = setOf(
        "dry-run",
        "dry-run-scope",
        "allow-localhost",
        "allow-private-network",
        "profile-overrides",
        "severity-cap",
        "\$schema",
        "version",
    )

    private val KNOWN_KEBAB: Set<String> = ConfigSections.all + EXTRA_KEYS

    /** Every built-in top-level key (kebab-case); the schema doc lists exactly these. */
    val known: Set<String> get() = KNOWN_KEBAB
    private val KNOWN_CAMEL: Set<String> = KNOWN_KEBAB.map(ConfigKeyNormalizer::kebabToCamelCase).toSet()

    /**
     * Returns one warning message per unknown top-level key of [root], in file order. Keys in
     * [claimedKeys] (registered modules' `configKey`s) are read by their module and not reported.
     */
    fun warnings(
        root: JsonElement,
        claimedKeys: Set<String> = emptySet(),
    ): List<String> {
        val claimedCamel = claimedKeys.map(ConfigKeyNormalizer::kebabToCamelCase).toSet()
        val code = KarandashError.UNKNOWN_YAML_CONFIG_KEY.bracketCode
        return (root as? JsonObject)
            ?.keys
            ?.mapNotNull { key ->
                val camel = ConfigKeyNormalizer.kebabToCamelCase(key)
                when {
                    camel in KNOWN_CAMEL || camel in claimedCamel -> {
                        null
                    }

                    else -> {
                        val hint = SuggestionEngine
                            .closest(key, KNOWN_KEBAB, maxDistance = MODULE_ID_MAX_DISTANCE)
                            ?.let { " Did you mean '$it'?" }
                            .orEmpty()
                        "$code Unknown top-level config key '$key' is ignored.$hint"
                    }
                }
            }.orEmpty()
    }

    /**
     * Module-owned blocks of the normalized (camelCase) [root]: for each of [configKeys] that is not a built-in
     * top-level key and is present in [root], its block keyed by the module's own `configKey`.
     */
    fun claimed(
        root: JsonObject,
        configKeys: Set<String>,
    ): Map<String, JsonElement> =
        configKeys
            .mapNotNull { key ->
                val camel = ConfigKeyNormalizer.kebabToCamelCase(key)
                if (camel in KNOWN_CAMEL) null else root[camel]?.let { key to it }
            }.toMap()
}
