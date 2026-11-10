package io.karandash.config.pipeline

import io.karandash.api.ModuleRegistry
import io.karandash.api.errors.KarandashError
import io.karandash.api.errors.MissingPluginException
import io.karandash.config.keys.AiReporterKeys
import io.karandash.config.keys.ModuleIds
import io.karandash.config.schema.ConfigSections
import io.karandash.manifest.ManifestData
import io.karandash.manifest.MissingPluginBehavior
import io.karandash.normalize.ConfigKeyNormalizer.camelToKebabCase
import io.karandash.normalize.ConfigKeyNormalizer.kebabToCamelCase
import io.karandash.normalize.ModuleIdNormalizer
import io.karandash.utils.SuggestionEngine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Detects config sections that reference modules not present in [ModuleRegistry].
 *
 * Scans the top-level sections of the merged raw config map for rule/reporter/provider/mutator/
 * comment-poster keys that do not correspond to any loaded module. Applies [behavior]
 * to determine whether to fail, warn, or silently skip.
 *
 * @param registry The registry populated by plugin loading.
 * @param manifestData The parsed manifest (for cross-referencing declared plugins).
 * @param behavior How to handle unknown module references.
 * @param warn Callback for emitting WARNING lines to the user.
 */
class MissingModuleDetector(
    private val registry: ModuleRegistry,
    private val manifestData: ManifestData,
    private val behavior: MissingPluginBehavior,
    private val warn: (String) -> Unit,
) {
    /**
     * Describes a config section that can be checked for unknown module references.
     *
     * @property configKey Top-level config key (e.g. "rules", "providers").
     * @property isKnown Predicate that returns true if a given ID is registered for this section.
     */
    @Serializable
    private data class SectionDef(
        val configKey: String,
        val isKnown: (String) -> Boolean,
    )

    /**
     * Describes a single unknown module reference.
     *
     * @property id The unknown module ID.
     * @property section Which config section it appeared in (e.g. "rules").
     * @property isDeclared True if the ID appears in [ManifestData.pluginDeclarations]
     *   (declared but not installed); false if completely unknown.
     */
    @Serializable
    data class UnknownReference(
        val id: String,
        val section: String,
        val isDeclared: Boolean,
    )

    /**
     * Check [rawElement] for unknown module references.
     *
     * @param rawElement The raw config as a [JsonElement].
     * @return Cleaned element with unknown sections removed (WARN/IGNORE modes).
     *   In FAIL mode, throws [MissingPluginException] instead.
     * @throws MissingPluginException E0302 (declared, not installed) or E0303 (unknown).
     */
    suspend fun check(rawElement: JsonElement): JsonElement {
        val obj = rawElement as? JsonObject ?: return rawElement
        val unknowns = collectUnknownsFromJson(obj)
        if (unknowns.isEmpty()) return rawElement

        return when (behavior) {
            MissingPluginBehavior.FAIL -> {
                throwFailError(unknowns)
            }

            MissingPluginBehavior.WARN -> {
                emitWarnings(unknowns)
                stripUnknownSectionsFromJson(obj, unknowns)
            }

            MissingPluginBehavior.IGNORE -> {
                stripUnknownSectionsFromJson(obj, unknowns)
            }
        }
    }

    private fun sections(): List<SectionDef> {
        val knownIds = registry.registeredIds()
        val pseudoKeys = registry
            .getByType(io.karandash.api.ModuleType.RULE)
            .filterIsInstance<io.karandash.api.support.PseudoModuleMarker>()
            .flatMap { it.pseudoConfigKeys() }
            .toSet()
        // AI analyzers are configured under `rules.<analyzerId>` (e.g. `risk-scoring`) but are not rule modules
        val analyzerIds = registry
            .getByType(io.karandash.api.ModuleType.AI_ANALYZER)
            .filterIsInstance<io.karandash.api.ai.AiAnalyzerModule>()
            .map { it.analyzerId }
            .toSet()
        return listOf(
            SectionDef(ConfigSections.RULES) { id ->
                registry.hasRule(id) ||
                    ModuleIds.RULE_ALIASES[id]?.let(registry::hasRule) == true ||
                    id in knownIds ||
                    id in pseudoKeys ||
                    id in analyzerIds
            },
            SectionDef(ConfigSections.REPORTERS) { key ->
                val id = ModuleIds.REPORTER_ALIASES[key] ?: key
                registry.reporters().any { sameKey(it.configKey, id) } ||
                    registry.formats().any { sameKey(it.configKey, id) } ||
                    registry.outputs().any { sameKey(it.configKey, id) } ||
                    (kebabToCamelCase(id) in AiReporterKeys.ALL && registry.aiReports().isNotEmpty())
            },
            SectionDef(ConfigSections.PLATFORMS) { id -> registry.platformServices().any { sameKey(it.configKey, id) } },
        )
    }

    /** Installed-but-missing, an unknown rule ([KarandashError.UNKNOWN_RULE_NAME]) or another unknown module. */
    private fun errorFor(u: UnknownReference): KarandashError =
        when {
            u.isDeclared -> KarandashError.PLUGIN_NOT_INSTALLED
            u.section == ConfigSections.RULES -> KarandashError.UNKNOWN_RULE_NAME
            else -> KarandashError.UNKNOWN_MODULE_REFERENCE
        }

    private suspend fun throwFailError(unknowns: List<UnknownReference>): Nothing {
        if (unknowns.size == 1) {
            val u = unknowns.first()
            val error = errorFor(u)
            val suggestion = buildSuggestion(u)
            throw MissingPluginException(
                "${error.bracketCode} Plugin module not installed: '${u.id}'\n" +
                    "  Referenced in config: ${u.section}.${u.id}\n" +
                    (
                        if (u.isDeclared) {
                            "  Status: declared in manifest but not installed\n  Fix: karandash plugin install --registry ${u.id}\n"
                        } else {
                            "  Status: not installed, not in manifest.yaml\n"
                        }
                    ) +
                    (if (suggestion != null) "  Did you mean: '$suggestion'?\n" else "") +
                    "  To skip this check: add 'plugins.on-missing: warn' to manifest.yaml"
            )
        }

        val lines = buildString {
            appendLine(
                "[${KarandashError.PLUGIN_NOT_INSTALLED.code}/${KarandashError.UNKNOWN_MODULE_REFERENCE.code}]" +
                    " Config references ${unknowns.size} unloaded plugin modules:"
            )
            appendLine()
            for (u in unknowns) {
                val marker = if (u.isDeclared) "x" else "?"
                val status = if (u.isDeclared) {
                    "declared in manifest, not installed"
                } else {
                    "unknown" + (buildSuggestion(u)?.let { " (did you mean '$it'?)" } ?: "")
                }
                appendLine("  $marker ${u.id.padEnd(30)} ${u.section}.${u.id.padEnd(30)} $status")
            }
            appendLine()
            val hasDeclared = unknowns.any { it.isDeclared }
            val hasUnknown = unknowns.any { !it.isDeclared }
            if (hasDeclared) appendLine("  Fix declared plugins: karandash plugin install --registry <id>")
            if (hasUnknown) appendLine("  Fix unknown modules:  check spelling or add to manifest.yaml")
        }
        throw MissingPluginException(lines.trim())
    }

    private suspend fun emitWarnings(unknowns: List<UnknownReference>) {
        for (u in unknowns) {
            val error = errorFor(u)
            val suggestion = buildSuggestion(u)
            val msg = buildString {
                append("WARNING ${error.bracketCode} ")
                if (u.isDeclared) {
                    append("Plugin declared in manifest but not installed: '${u.id}'")
                    append(" (${u.section}). Run 'karandash plugin install --registry ${u.id}' to install it.")
                } else {
                    append("Unknown plugin module: '${u.id}' (${u.section})")
                    if (suggestion != null) {
                        append(". Did you mean '$suggestion'?")
                    } else {
                        append(". Add to manifest.yaml if intentional.")
                    }
                }
            }
            warn(msg)
        }
    }

    private fun collectUnknownsFromJson(obj: JsonObject): List<UnknownReference> {
        // The merged config has camelCase keys; ids and declarations may be kebab-case.
        val knownIds = registry.registeredIds().map(::kebabToCamelCase).toSet()
        val declaredIds = manifestData.pluginDeclarations.associateBy({ kebabToCamelCase(it.id) }, { it.id })
        val unknowns = mutableListOf<UnknownReference>()

        for (section in sections()) {
            val sectionObj = obj[section.configKey] as? JsonObject ?: continue
            for (key in sectionObj.keys) {
                val camelKey = kebabToCamelCase(key)
                // rule ids, aliases and pseudo keys are kebab-case (`risk-scoring`)
                val isKnown = section.isKnown(key) || section.isKnown(camelToKebabCase(key)) || camelKey in knownIds
                if (!isKnown) {
                    unknowns.add(
                        UnknownReference(
                            id = declaredIds[camelKey] ?: key,
                            section = section.configKey,
                            isDeclared = camelKey in declaredIds,
                        )
                    )
                }
            }
        }
        return unknowns
    }

    private fun stripUnknownSectionsFromJson(
        obj: JsonObject,
        unknowns: List<UnknownReference>,
    ): JsonObject {
        if (unknowns.isEmpty()) return obj
        val unknownBySectionId = unknowns.groupBy({ it.section }, { kebabToCamelCase(it.id) })
        val result = obj.toMutableMap()
        for ((sectionKey, ids) in unknownBySectionId) {
            val sectionObj = result[sectionKey] as? JsonObject ?: continue
            val cleaned = JsonObject(sectionObj.filterKeys { kebabToCamelCase(it) !in ids })
            result[sectionKey] = cleaned
        }
        return JsonObject(result)
    }

    private fun sameKey(
        a: String,
        b: String,
    ): Boolean = ModuleIdNormalizer.matches(a, b)

    private suspend fun buildSuggestion(u: UnknownReference): String? {
        if (u.isDeclared) return null
        val configKeys = buildSet {
            addAll(registry.registeredIds())
            registry.ruleModules().flatMap { it.contributedRules() }.forEach { add(it) }
            registry.reporters().forEach { add(it.configKey) }
            registry.outputs().forEach { add(it.configKey) }
            registry.platformServices().forEach { add(it.configKey) }
        }
        val declaredIds = manifestData.pluginDeclarations.map { it.id }.toSet()
        return SuggestionEngine.closest(u.id, configKeys + declaredIds, maxDistance = MODULE_ID_MAX_DISTANCE)
    }
}
