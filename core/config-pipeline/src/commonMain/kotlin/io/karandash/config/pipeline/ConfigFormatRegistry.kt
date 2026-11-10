package io.karandash.config.pipeline

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.ModuleRegistry
import io.karandash.api.config.ConfigProperty
import io.karandash.api.config.ConfigPropertyType
import io.karandash.api.module.ConfigFormatModule
import io.karandash.api.module.SchemaAwareFormatModule
import io.karandash.sdk.config.ConfigFormatModuleAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Registry of available config format modules and renderers.
 *
 * Built-in formats (yaml, json, kts) are registered at construction time.
 * Community format plugins can register additional entries via [register].
 *
 * @param builtIns Built-in format modules to register immediately.
 * @param builtInRenderers Built-in renderer modules to register immediately.
 *   Defaults to the standard yaml, json, and kts renderers.
 *   Pass a custom list to override or extend the available renderers.
 */
class ConfigFormatRegistry(
    builtIns: List<ConfigFormatModule> = emptyList(),
    builtInRenderers: List<ConfigRendererModule> = emptyList(),
) {
    private val modules = MutableStateFlow<Map<String, ConfigFormatModule>>(emptyMap())
    private val renderers = MutableStateFlow<Map<String, ConfigRendererModule>>(emptyMap())

    init {
        builtIns.forEach { register(it) }
        builtInRenderers.forEach { registerRenderer(it) }
    }

    /**
     * Register a format module by its [ConfigFormatModule.formatId].
     * Overwrites any existing registration for the same format id.
     */
    fun register(module: ConfigFormatModule) {
        modules.update { it + (module.formatId to module) }
    }

    /**
     * Detect the format of a config file by URL extension.
     *
     * Returns null when no registered module recognises the extension.
     *
     * @param url File URL or path.
     * @return The detected format id (e.g. "yaml"), or null if unrecognised.
     */
    fun detectFormatOrNull(url: String): String? {
        val ext = url.substringAfterLast('.', "").lowercase()
        val snapshot = modules.value
        return snapshot.values.firstOrNull { ext in it.fileExtensions }?.formatId
            ?: snapshot.keys.firstOrNull { it == ext }
    }

    /**
     * Detect the format of a config file by URL extension.
     *
     * Looks up file extensions declared by registered format modules.
     * Falls back to the default format (first registered module) for URLs
     * with unrecognised or absent extensions (e.g. remote URLs without a file extension).
     *
     * @param url File URL or path.
     * @return The detected format id (e.g. "yaml"), or the default format as fallback.
     */
    fun detectFormat(url: String): String = detectFormatOrNull(url) ?: defaultFormatId

    /**
     * Look up the format module for a given file URL or path.
     *
     * Detects the format from the URL extension and returns the corresponding module,
     * or null if no registered module recognises the extension.
     *
     * @param url File URL or path.
     * @return The [ConfigFormatModule] for the detected format, or null if unrecognised.
     */
    fun getModuleForFile(url: String): ConfigFormatModule? {
        val formatId = detectFormatOrNull(url) ?: return null
        return modules.value[formatId]
    }

    /**
     * Look up a format module by id.
     *
     * @throws IllegalArgumentException if the format id is not registered.
     */
    fun getModule(formatId: String): ConfigFormatModule =
        modules.value[formatId]
            ?: throw IllegalArgumentException(
                "No config format module registered for '$formatId'. Available: ${modules.value.keys}"
            )

    /**
     * The default format id, used as the fallback when format detection
     * cannot determine the type from a file extension (e.g. remote URLs).
     *
     * Prefers "yaml" if registered (the most common data format for configs),
     * then falls back to the highest-priority registered module.
     */
    val defaultFormatId: String
        get() {
            val snapshot = modules.value
            return snapshot[FALLBACK_FORMAT_ID]?.formatId
                ?: snapshot.values.minByOrNull { it.priority }?.formatId
                ?: FALLBACK_FORMAT_ID
        }

    /**
     * Returns all registered format modules, sorted by [ConfigFormatModule.priority]
     * (lowest value first).
     */
    fun allModules(): List<ConfigFormatModule> = modules.value.values.sortedBy { it.priority }

    /**
     * Returns all file extensions supported by registered format modules.
     */
    fun allExtensions(): Set<String> = modules.value.values.flatMapTo(mutableSetOf()) { it.fileExtensions }

    /**
     * Register a renderer module by its [ConfigRendererModule.formatId].
     * Overwrites any existing registration for the same format id.
     */
    fun registerRenderer(renderer: ConfigRendererModule) {
        renderers.update { it + (renderer.formatId to renderer) }
    }

    /**
     * Look up a renderer module by format id.
     *
     * @throws IllegalArgumentException if the format id is not registered.
     */
    fun getRenderer(formatId: String): ConfigRendererModule =
        renderers.value[formatId]
            ?: throw IllegalArgumentException(
                "No config renderer registered for '$formatId'. Available: ${renderers.value.keys}"
            )

    /**
     * Look up a renderer module by format id, returning null if not registered.
     */
    fun getRendererOrNull(formatId: String): ConfigRendererModule? = renderers.value[formatId]

    /**
     * Returns all registered renderer modules.
     */
    fun allRenderers(): List<ConfigRendererModule> = renderers.value.values.toList()

    /**
     * Returns the default renderer (first registered, typically YAML).
     *
     * @throws NoSuchElementException if no renderers are registered.
     */
    fun defaultRenderer(): ConfigRendererModule = renderers.value.values.first()

    /**
     * Returns the ordered list of candidate config filenames to search during discovery.
     *
     * Candidates are derived from each module's [ConfigFormatModule.fileExtensions] using
     * the tool's naming convention. For each extension three variants are generated:
     * - `.karandash/config.<ext>` (subdirectory form)
     * - `.karandash.<ext>` (dotfile form)
     * - `karandash.<ext>` (plain form)
     *
     * Modules are sorted by [ConfigFormatModule.priority] (lowest value first),
     * so higher-priority formats are tried before lower-priority ones.
     */
    fun allCandidates(): List<String> =
        modules.value.values.sortedBy { it.priority }.flatMap { module ->
            module.fileExtensions.flatMap { ext ->
                listOf(".karandash/config.$ext", ".karandash.$ext", "karandash.$ext")
            }
        }

    companion object {
        private const val FALLBACK_FORMAT_ID = "yaml"
        private const val OBJECT_TYPE = "object"

        /**
         * Creates a [ConfigFormatRegistry] populated from a [ModuleRegistry].
         *
         * All [ConfigFormatModule] instances registered in the module registry
         * are added to the format registry. A format implementing [SchemaAwareFormatModule]
         * is registered as the instance its [SchemaAwareFormatModule.withRuleSchemas] returns for the
         * registry's rule schemas (rule ID to JSON Schema).
         *
         * @param registry Module registry for format module lookup.
         */
        @OptIn(ExperimentalKarandashApi::class)
        suspend fun fromRegistry(registry: ModuleRegistry): ConfigFormatRegistry {
            val reg = ConfigFormatRegistry()
            val jsonSchemas = buildJsonSchemas(registry)
            for (module in registry.configFormats()) {
                val delegate = (module as? ConfigFormatModuleAdapter)?.delegate ?: module
                if (delegate !is SchemaAwareFormatModule) {
                    reg.register(module)
                    continue
                }
                val configured = delegate.withRuleSchemas(jsonSchemas)
                reg.register(if (configured === delegate) module else configured)
            }
            return reg
        }

        /** Rule ID to JSON Schema (`type`, `properties`, `required`) built from each rule module's config schema. */
        private suspend fun buildJsonSchemas(registry: ModuleRegistry): JsonObject =
            buildJsonObject {
                for (module in registry.ruleModules()) {
                    val props = module.configSchema()
                    if (props.isEmpty()) continue
                    val schema = ruleJsonSchema(props)
                    for (ruleId in module.contributedRules()) put(ruleId, schema)
                }
            }

        @OptIn(ExperimentalKarandashApi::class)
        private fun ruleJsonSchema(properties: List<ConfigProperty>): JsonObject =
            buildJsonObject {
                put("type", OBJECT_TYPE)
                putJsonObject("properties") {
                    for (prop in properties) {
                        putJsonObject(prop.name) {
                            putPropertyType(prop.type)
                            prop.defaultJson?.let { put("default", it) }
                        }
                    }
                }
                putJsonArray("required") {
                    properties.filter { it.required }.forEach { add(it.name) }
                }
            }

        private fun JsonObjectBuilder.putPropertyType(type: ConfigPropertyType) {
            when (type) {
                ConfigPropertyType.STRING, ConfigPropertyType.ENUM -> put("type", "string")
                ConfigPropertyType.INT -> put("type", "integer")
                ConfigPropertyType.BOOLEAN -> put("type", "boolean")
                ConfigPropertyType.OBJECT -> put("type", OBJECT_TYPE)
                ConfigPropertyType.STRING_LIST -> putArrayOf("string")
                ConfigPropertyType.OBJECT_LIST -> putArrayOf(OBJECT_TYPE)
            }
        }

        private fun JsonObjectBuilder.putArrayOf(itemType: String) {
            put("type", "array")
            putJsonObject("items") { put("type", itemType) }
        }
    }
}
