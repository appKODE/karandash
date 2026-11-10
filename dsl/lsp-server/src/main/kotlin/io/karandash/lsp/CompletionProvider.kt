package io.karandash.lsp

import io.karandash.api.config.ConfigPropertyType
import io.karandash.config.schema.ConfigSections
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind

/**
 * Provides context-aware code completion for Karandash DSL,
 * driven by the [ModuleRegistry] rather than hardcoded metadata.
 */
class CompletionProvider(
    private val metadata: CachedRegistryMetadata,
) {
    /** Get completions at a specific position in the document. */
    fun getCompletions(
        documentText: String,
        line: Int,
        character: Int,
    ): List<CompletionItem> {
        val context = determineContext(documentText, line, character)
        return getCompletionsForContext(context)
    }

    /** Determine the cursor context by analyzing the document. */
    private fun determineContext(
        documentText: String,
        line: Int,
        character: Int,
    ): CompletionContext {
        val lines = documentText.split("\n")
        if (line >= lines.size) return CompletionContext.TopLevel

        val currentLine = lines[line]
        val textBeforeCursor = currentLine.substring(0, minOf(character, currentLine.length))
        val precedingText = lines.take(line + 1).joinToString("\n")
        val nestLevel = DslContextResolver.countNetBraces(precedingText)
        val blockContext = DslContextResolver.findBlockContext(precedingText)

        return when {
            nestLevel == 0 -> {
                CompletionContext.TopLevel
            }

            blockContext == ConfigSections.RULES && nestLevel == 1 -> {
                CompletionContext.RulesBlock
            }

            blockContext == ConfigSections.REPORTERS && nestLevel == 1 -> {
                CompletionContext.ReportersBlock
            }

            blockContext == ConfigSections.PLATFORMS && nestLevel == 1 -> {
                CompletionContext.PlatformsBlock
            }

            blockContext == "profile" && nestLevel == 1 -> {
                CompletionContext.ProfileBlock
            }

            blockContext == ConfigSections.POLICIES && nestLevel == 1 -> {
                CompletionContext.PoliciesBlock
            }

            blockContext.startsWith("${ConfigSections.RULES}.") -> {
                val validatorName = blockContext.removePrefix("${ConfigSections.RULES}.")
                CompletionContext.ValidatorBlock(validatorName)
            }

            textBeforeCursor.contains("=") && !textBeforeCursor.trim().endsWith("=") -> {
                CompletionContext.PropertyValue
            }

            textBeforeCursor.trim().endsWith("=") -> {
                CompletionContext.PropertyValue
            }

            else -> {
                CompletionContext.TopLevel
            }
        }
    }

    private fun getCompletionsForContext(context: CompletionContext): List<CompletionItem> =
        when (context) {
            CompletionContext.TopLevel -> getTopLevelCompletions()
            CompletionContext.RulesBlock -> getValidatorCompletions()
            CompletionContext.ReportersBlock -> getReporterCompletions()
            CompletionContext.PlatformsBlock -> getProviderCompletions()
            CompletionContext.ProfileBlock -> getProfilePropertyCompletions()
            CompletionContext.PoliciesBlock -> getPolicyEntryCompletions()
            is CompletionContext.ValidatorBlock -> getValidatorPropertyCompletions(context.validatorName)
            CompletionContext.PropertyValue -> emptyList()
        }

    private fun getTopLevelCompletions(): List<CompletionItem> =
        ConfigSections.all
            .filter { it != ConfigSections.PROFILE }
            .map { section ->
                createBlockCompletion(section, "Configuration block for $section")
            } + createProfileCompletion()

    /** Validator completions inside rules block, built from the registry. */
    private fun getValidatorCompletions(): List<CompletionItem> =
        metadata.ruleIds.map { ruleId -> createBlockCompletion(ruleId, "Rule: $ruleId") }

    /**
     * Reporter completions inside reporters block, built from the registry.
     *
     * Reporters with a non-empty [configSchema] are presented as block completions;
     * reporters with an empty schema are presented as boolean property completions.
     */
    private fun getReporterCompletions(): List<CompletionItem> =
        metadata.blockReporterIds.map { id -> createBlockCompletion(id, "Reporter: $id") } +
            metadata.propertyReporterIds.map { id ->
                createPropertyCompletion(id, ConfigPropertyType.BOOLEAN.displayName(), "Reporter: $id")
            }

    /** Provider completions inside providers block, built from the registry. */
    private fun getProviderCompletions(): List<CompletionItem> =
        metadata.providerIds.map { id -> createBlockCompletion(id, "Provider: $id") }

    /**
     * Property completions for a specific validator, built from [io.karandash.api.Module.configSchema].
     * Always appends [RULE_META_COMPLETIONS] for execution-meta keys (`when`, `priority`, `bailOnFail`).
     */
    private fun getValidatorPropertyCompletions(validatorName: String): List<CompletionItem> {
        val keys = metadata.ruleConfigKeys[validatorName] ?: emptyList()
        val schemaCompletions = keys.map { key ->
            val type = metadata.rulePropertyTypes[validatorName to key]
            createPropertyCompletion(key, type?.displayName() ?: DETAIL_STRING, "Config property")
        }
        return schemaCompletions + RULE_META_COMPLETIONS
    }

    /**
     * Completions inside a `policies {}` block - offers a `policy { }` snippet with
     * all known policy properties pre-filled.
     */
    private fun getPolicyEntryCompletions(): List<CompletionItem> =
        listOf(
            snippetItem(
                "policy",
                CompletionItemKind.Keyword,
                DETAIL_BLOCK,
                "A single conditional policy entry.",
                "policy {\n\twhen = \"$1\"\n\t$0\n}",
            ),
        ) + POLICY_PROPERTY_COMPLETIONS

    /** Profile completion for the top-level `profile` key. */
    private fun createProfileCompletion(): CompletionItem =
        snippetItem(
            "profile",
            CompletionItemKind.Property,
            ConfigPropertyType.STRING.displayName(),
            "Select a predefined rule configuration profile",
            "profile = \"$0\"",
        )

    /** Property completions inside profile block (extended form). */
    private fun getProfilePropertyCompletions(): List<CompletionItem> =
        listOf(
            createPropertyCompletion("id", ConfigPropertyType.STRING.displayName(), "Profile ID (e.g. conventional-commits, github-flow)"),
            createPropertyCompletion("severity", ConfigPropertyType.STRING.displayName(), "Default severity: warning or error"),
        )
}

/** Maps [ConfigPropertyType] to a human-readable display name for completion detail. */
private fun ConfigPropertyType.displayName(): String =
    when (this) {
        ConfigPropertyType.STRING -> "String"
        ConfigPropertyType.INT -> "Int"
        ConfigPropertyType.BOOLEAN -> "Boolean"
        ConfigPropertyType.STRING_LIST -> "List<String>"
        ConfigPropertyType.OBJECT -> "Object"
        ConfigPropertyType.OBJECT_LIST -> "List<Object>"
        ConfigPropertyType.ENUM -> "Enum"
    }
