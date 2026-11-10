package io.karandash.cli.templates

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import net.mamoe.yamlkt.Yaml
import net.mamoe.yamlkt.YamlElement
import net.mamoe.yamlkt.YamlList
import net.mamoe.yamlkt.YamlLiteral
import net.mamoe.yamlkt.YamlMap
import net.mamoe.yamlkt.YamlNull

/**
 * Registry for loading and managing YAML template presets.
 * Supports base templates and platform-specific templates.
 *
 * Uses EmbeddedTemplates for template content (multiplatform) and yamlkt for YAML parsing.
 */
object PresetRegistry {
    private val baseTemplates = MutableStateFlow<Map<String, Map<String, Any>>>(emptyMap())
    private val platformTemplates = MutableStateFlow<Map<String, Map<String, Any>>>(emptyMap())
    private val customCategories = MutableStateFlow<Map<String, Map<String, Map<String, Any>>>>(emptyMap())

    private val initialized = MutableStateFlow(false)

    /**
     * Load all YAML templates from embedded content.
     * Must be called before accessing templates.
     */
    fun initialize() {
        if (initialized.value) return

        baseTemplates.value = loadCategory("base")
        platformTemplates.value = loadCategory("platforms")

        val customSnapshot = customCategories.value
        for ((category, _) in customSnapshot) {
            customCategories.update { current ->
                current + (category to (current[category].orEmpty() + loadCategory(category)))
            }
        }

        initialized.value = true
    }

    /**
     * Get a base template by name.
     * @return Template map or null if not found
     */
    fun getBaseTemplate(name: String): Map<String, Any>? {
        ensureInitialized()
        return baseTemplates.value[name]
    }

    /**
     * Get a platform template by name.
     * @return Template map or null if not found
     */
    fun getPlatformTemplate(name: String): Map<String, Any>? {
        ensureInitialized()
        return platformTemplates.value[name]
    }

    /**
     * List all available base template names.
     */
    fun listBaseTemplates(): List<String> {
        ensureInitialized()
        return baseTemplates.value.keys.sorted()
    }

    /**
     * List all available platform template names.
     */
    fun listPlatformTemplates(): List<String> {
        ensureInitialized()
        return platformTemplates.value.keys.sorted()
    }

    /**
     * Register a custom template category with pre-parsed template maps.
     *
     * Templates added here are accessible via [getCustomTemplate] and included
     * in the category targets iterated during [initialize].
     *
     * @param name Category name (must not conflict with "base" or "platforms")
     * @param templates Map of template name to parsed YAML map
     */
    fun registerCategory(
        name: String,
        templates: Map<String, Map<String, Any>>
    ) {
        customCategories.update { current ->
            val existing = current[name].orEmpty()
            current + (name to (existing + templates))
        }
    }

    /**
     * Get a template from a custom category by category and template name.
     * @return Template map or null if not found
     */
    fun getCustomTemplate(
        category: String,
        name: String
    ): Map<String, Any>? {
        ensureInitialized()
        return customCategories.value[category]?.get(name)
    }

    /**
     * List all available template names in a custom category.
     */
    fun listCustomTemplates(category: String): List<String> {
        ensureInitialized()
        return customCategories.value[category]?.keys?.sorted() ?: emptyList()
    }

    /**
     * Clear all loaded templates (primarily for testing).
     */
    internal fun reset() {
        baseTemplates.value = emptyMap()
        platformTemplates.value = emptyMap()
        customCategories.value = emptyMap()
        initialized.value = false
    }

    private fun ensureInitialized() {
        if (!initialized.value) {
            initialize()
        }
    }

    private fun loadCategory(category: String): Map<String, Map<String, Any>> {
        val result = mutableMapOf<String, Map<String, Any>>()
        EmbeddedTemplates.keys(category).forEach { name ->
            loadTemplate(category, name)?.let { map ->
                result[name] = map
            }
        }
        return result
    }

    private fun loadTemplate(
        category: String,
        name: String
    ): Map<String, Any>? {
        return try {
            val yamlContent = EmbeddedTemplates.get(category, name) ?: return null
            val element = Yaml.decodeYamlFromString(yamlContent)
            when (element) {
                is YamlMap -> yamlMapToMap(element)
                else -> null
            }
        } catch (ignored: IllegalArgumentException) {
            // Silently fail for missing or malformed templates
            null
        }
    }

    private fun yamlMapToMap(yamlMap: YamlMap): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        for ((key, value) in yamlMap) {
            val keyStr = when (key) {
                is YamlLiteral -> key.content
                else -> key.toString()
            }
            val converted = yamlElementToAny(value)
            if (converted != null) {
                result[keyStr] = converted
            }
        }
        return result
    }

    private fun yamlElementToAny(element: YamlElement): Any? =
        when (element) {
            is YamlNull -> {
                null
            }

            is YamlLiteral -> {
                val content = element.content
                when {
                    content == "null" -> null
                    content == "true" -> true
                    content == "false" -> false
                    content.toIntOrNull() != null && !content.contains(".") -> content.toInt()
                    else -> content
                }
            }

            is YamlMap -> {
                yamlMapToMap(element)
            }

            is YamlList -> {
                element.map { yamlElementToAny(it) ?: "" }
            }
        }
}
