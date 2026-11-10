package io.karandash.config.pipeline

import io.karandash.api.ModuleRegistry
import io.karandash.api.config.ConfigProperty
import io.karandash.api.config.ConfigPropertyType
import io.karandash.config.schema.ConfigSections
import io.karandash.normalize.ConfigKeyNormalizer
import io.karandash.platform.absolutePathOrSelf
import io.karandash.platform.writeText
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable

/**
 * Generates a JSON Schema 2020-12 document describing all loaded module configurations.
 *
 * The generated schema covers the top-level config file structure (rules, reporters,
 * platforms, reporters) and each module's known config properties.
 *
 * Write `.karandash.schema.json` to the project root for IDE autocompletion (e.g. VS Code).
 *
 * @param registry The loaded module registry (built-in + plugins).
 */
class SchemaJsonGenerator(
    private val registry: ModuleRegistry
) {
    /**
     * Descriptor for a non-rules config section in the generated JSON Schema.
     *
     * @property configKey Top-level config key (e.g. [ConfigSections.PLATFORMS]).
     * @property description Human-readable description for the schema.
     * @property body JSON members following the description: the section's `type` and, for objects, `additionalProperties`.
     */
    @Serializable
    private data class SchemaSectionDef(
        val configKey: String,
        val description: String,
        val body: String,
    )

    private val builtInCamel: Set<String> =
        (UnknownTopLevelKeys.known + ConfigSections.RULES + "extends").map(ConfigKeyNormalizer::kebabToCamelCase).toSet()

    private companion object {
        private const val JSON_SECTION_CLOSE = "    },\n"
        private const val OBJECT_OPEN = "\"type\": \"object\", \"additionalProperties\": true"
        private const val OBJECT_OF_OBJECTS = "\"type\": \"object\", \"additionalProperties\": {\"type\": \"object\"}"
        private const val OBJECT_OF_STRINGS = "\"type\": \"object\", \"additionalProperties\": {\"type\": \"string\"}"
        private const val OBJECT_OF_BOOL_OR_OBJECT =
            "\"type\": \"object\", \"additionalProperties\": {\"oneOf\": [{\"type\": \"boolean\"}, {\"type\": \"object\"}]}"
        private const val ARRAY_OF_OBJECTS = "\"type\": \"array\", \"items\": {\"type\": \"object\"}"
        private const val STRING_OR_OBJECT = "\"oneOf\": [{\"type\": \"string\"}, {\"type\": \"object\"}]"
        private const val STRING_TYPE = "\"type\": \"string\""
        private const val BOOLEAN_TYPE = "\"type\": \"boolean\""
        private const val ANY_VALUE = "\"additionalProperties\": true"
        private const val OBJECT_OR_ARRAY = "\"type\": [\"object\", \"array\"]"
    }

    /** One entry per top-level section except `rules` and `extends`, which are emitted separately. */
    private fun schemaSectionDefs(): List<SchemaSectionDef> =
        listOf(
            SchemaSectionDef(
                ConfigSections.PLATFORMS,
                "Platform service configuration (provider + mutator + comment posting)",
                OBJECT_OF_OBJECTS
            ),
            SchemaSectionDef(ConfigSections.REPORTERS, "Output reporter configuration", OBJECT_OF_BOOL_OR_OBJECT),
            SchemaSectionDef(ConfigSections.SCRIPTS, "Scripts run around validation", ARRAY_OF_OBJECTS),
            SchemaSectionDef(ConfigSections.ENV, "Environment variables exposed to scripts and plugins", OBJECT_OF_STRINGS),
            SchemaSectionDef(ConfigSections.AI, "AI analysis configuration", OBJECT_OPEN),
            SchemaSectionDef(ConfigSections.AUTO_APPROVE, "Automatic approval when findings stay below a severity", OBJECT_OPEN),
            SchemaSectionDef(ConfigSections.CACHE, "Cache settings", OBJECT_OPEN),
            SchemaSectionDef(ConfigSections.PROFILE, "Profile to start from: an id or an object with overrides", STRING_OR_OBJECT),
            SchemaSectionDef(ConfigSections.DANGERFILE, "Path to a Dangerfile script", STRING_TYPE),
            SchemaSectionDef(ConfigSections.BASELINE, "Fingerprint file of findings that are not reported (--baseline wins)", STRING_TYPE),
            SchemaSectionDef(ConfigSections.OPTIONS, "Run options", OBJECT_OPEN),
            SchemaSectionDef(ConfigSections.PATTERNS, "Named patterns usable in conditions", OBJECT_OF_STRINGS),
            SchemaSectionDef(ConfigSections.POLICIES, "Conditional policies applied to rules and modules", ARRAY_OF_OBJECTS),
            SchemaSectionDef(ConfigSections.AUTH, "Authentication settings", OBJECT_OPEN),
            SchemaSectionDef(ConfigSections.TIMEOUTS, "Timeout settings", OBJECT_OPEN),
            SchemaSectionDef(ConfigSections.HTTP, "HTTP client settings", OBJECT_OPEN),
            SchemaSectionDef(ConfigSections.SECURITY, "Security settings (blocked hosts)", OBJECT_OPEN),
            SchemaSectionDef(ConfigSections.LIMITS, "Resource limits", OBJECT_OPEN),
            SchemaSectionDef(ConfigSections.DISPLAY, "Console display settings", OBJECT_OPEN),
            SchemaSectionDef(ConfigSections.REPORT, "Report settings", OBJECT_OPEN),
            SchemaSectionDef(ConfigSections.DIAGNOSTICS, "Diagnostics settings", OBJECT_OPEN),
            SchemaSectionDef(ConfigSections.ENRICHMENT, "Enrichment settings", OBJECT_OPEN),
            SchemaSectionDef(ConfigSections.RULE_ENRICHMENT, "Per-rule enrichment settings", OBJECT_OF_OBJECTS),
            SchemaSectionDef(ConfigSections.PLUGINS, "Plugin declarations and registry settings", OBJECT_OR_ARRAY),
            SchemaSectionDef("\$schema", "Schema URL for editor completion", STRING_TYPE),
            SchemaSectionDef(
                "version",
                "Config schema version (major.minor); a newer one than this karandash supports fails the load",
                "\"type\": [\"string\", \"number\"]"
            ),
            SchemaSectionDef("dry-run", "Suppress side effects (see dry-run-scope)", BOOLEAN_TYPE),
            SchemaSectionDef("dry-run-scope", "What dry-run suppresses", STRING_TYPE),
            SchemaSectionDef("allow-localhost", "Allow outbound requests to localhost", BOOLEAN_TYPE),
            SchemaSectionDef("allow-private-network", "Allow outbound requests to private networks", BOOLEAN_TYPE),
            SchemaSectionDef("profile-overrides", "Per-profile rule overrides", OBJECT_OPEN),
            SchemaSectionDef("severity-cap", "Shadow mode: cap every finding at this severity (error, warning, info)", STRING_TYPE),
        ) + moduleClaimedDefs()

    /** Top-level blocks read by registered modules (their `configKey`) that are not built-in keys. */
    private fun moduleClaimedDefs(): List<SchemaSectionDef> =
        registry
            .allModules()
            .map { it.configKey }
            .filter { key -> ConfigKeyNormalizer.kebabToCamelCase(key) !in builtInCamel }
            .distinct()
            .map { SchemaSectionDef(it, "Configuration block read by the '$it' module", ANY_VALUE) }

    /**
     * Generate a JSON Schema string for the complete config format.
     */
    suspend fun generate(): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"\$schema\": \"https://json-schema.org/draft/2020-12/schema\",\n")
        sb.append("  \"title\": \"Karandash Configuration\",\n")
        sb.append("  \"description\": \"Karandash MR/PR validation tool configuration\",\n")
        sb.append("  \"type\": \"object\",\n")
        sb.append("  \"properties\": {\n")

        sb.append("    \"extends\": {\n")
        sb.append("      \"description\": \"Inherit configuration from other files or URLs\",\n")
        sb.append("      \"oneOf\": [{\"type\": \"string\"}, {\"type\": \"array\", \"items\": {\"type\": \"string\"}}]\n")
        sb.append(JSON_SECTION_CLOSE)

        generateRulesSection(sb)

        val sections = schemaSectionDefs()
        sections.forEachIndexed { idx, def ->
            val isLast = idx == sections.size - 1
            generateModuleSection(sb, def, isLast)
        }

        sb.append("  },\n")
        sb.append("  \"additionalProperties\": false\n")
        sb.append("}\n")
        return sb.toString()
    }

    private suspend fun generateRulesSection(sb: StringBuilder) {
        sb.append("    \"${ConfigSections.RULES}\": {\n")
        sb.append("      \"description\": \"Validation rules configuration\",\n")
        sb.append("      \"type\": \"object\",\n")
        sb.append("      \"properties\": {\n")
        val ruleModules = registry.ruleModules()
        ruleModules.forEachIndexed { idx, module ->
            val rules = module.contributedRules().toList()
            rules.forEachIndexed { rIdx, ruleId ->
                val schema = module.configSchema()
                sb.append("        \"$ruleId\": ")
                sb.append(moduleSchemaToJson(ruleId, schema, "        "))
                val isLastRule = idx == ruleModules.size - 1 && rIdx == rules.size - 1
                if (!isLastRule) sb.append(",")
                sb.append("\n")
            }
        }
        sb.append("      },\n")
        sb.append("      \"additionalProperties\": true\n")
        sb.append(JSON_SECTION_CLOSE)
    }

    private fun generateModuleSection(
        sb: StringBuilder,
        def: SchemaSectionDef,
        isLast: Boolean,
    ) {
        sb.append("    \"${def.configKey}\": {\n")
        sb.append("      \"description\": \"${def.description}\",\n")
        sb.append("      ${def.body}\n")
        if (isLast) {
            sb.append("    }\n")
        } else {
            sb.append(JSON_SECTION_CLOSE)
        }
    }

    /**
     * Write the generated schema to `.karandash.schema.json` in the given directory.
     *
     * @param directory Directory to write the schema file to (defaults to current dir).
     */
    suspend fun writeToFile(directory: String = ".") {
        Path("$directory/.karandash.schema.json").absolutePathOrSelf().writeText(generate())
    }

    private fun moduleSchemaToJson(
        moduleId: String,
        schema: List<ConfigProperty>,
        indent: String,
    ): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("$indent  \"description\": \"Configuration for $moduleId\",\n")
        sb.append("$indent  \"type\": \"object\",\n")
        if (schema.isEmpty()) {
            sb.append("$indent  \"additionalProperties\": true\n")
        } else {
            val required = schema.filter { it.required }.map { it.name }
            if (required.isNotEmpty()) {
                sb.append("$indent  \"required\": [${required.joinToString(", ") { "\"$it\"" }}],\n")
            }
            sb.append("$indent  \"properties\": {\n")
            schema.forEachIndexed { idx, prop ->
                sb.append("$indent    \"${prop.name}\": ")
                sb.append(propertyToJson(prop, "$indent    "))
                if (idx < schema.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("$indent  },\n")
            sb.append("$indent  \"additionalProperties\": true\n")
        }
        sb.append("$indent}")
        return sb.toString()
    }

    private fun propertyToJson(
        prop: ConfigProperty,
        indent: String,
    ): String {
        val sb = StringBuilder()
        sb.append("{\n")
        if (prop.description.isNotEmpty()) {
            sb.append("$indent  \"description\": \"${prop.description.replace("\"", "\\\"")}\",\n")
        }
        when (prop.type) {
            ConfigPropertyType.STRING -> {
                sb.append("$indent  \"type\": \"string\"\n")
            }

            ConfigPropertyType.INT -> {
                sb.append("$indent  \"type\": \"integer\"\n")
            }

            ConfigPropertyType.BOOLEAN -> {
                sb.append("$indent  \"type\": \"boolean\"\n")
            }

            ConfigPropertyType.STRING_LIST -> {
                sb.append("$indent  \"type\": \"array\",\n")
                sb.append("$indent  \"items\": {\"type\": \"string\"}\n")
            }

            ConfigPropertyType.OBJECT -> {
                sb.append("$indent  \"type\": \"object\"\n")
            }

            ConfigPropertyType.OBJECT_LIST -> {
                sb.append("$indent  \"type\": \"array\",\n")
                sb.append("$indent  \"items\": {\"type\": \"object\"}\n")
            }

            ConfigPropertyType.ENUM -> {
                val values = prop.enumValues ?: emptyList()
                sb.append("$indent  \"type\": \"string\"")
                if (values.isNotEmpty()) {
                    sb.append(",\n$indent  \"enum\": [${values.joinToString(", ") { "\"$it\"" }}]\n")
                } else {
                    sb.append("\n")
                }
            }
        }
        sb.append("$indent}")
        return sb.toString()
    }
}
