package io.karandash.plugins.ai.core.config

import io.karandash.api.config.PreserveKeys
import io.karandash.utils.GlobMatcher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Rule macros: reusable named policy bundles.
 *
 * Macros are expanded at config parse time. A [MacroReference] names a macro and the file
 * patterns it applies to. [expand] resolves references to their [ExpandedMacro] counterparts.
 * [findApplicableRules] then returns rule overrides for a given file path.
 */
object RuleMacroExpander {
    /**
     * Expands macro references by looking them up in the macro definitions map.
     * References pointing to unknown macros are dropped (the AI config applicator warns about each one).
     *
     * @param macros Named macro definitions.
     * @param references List of macro references to expand.
     * @return Expanded macros with their associated file patterns and rule overrides.
     */
    fun expand(
        macros: Map<String, MacroDefinition>,
        references: List<MacroReference>
    ): List<ExpandedMacro> =
        references.mapNotNull { ref ->
            val macro = macros[ref.useMacro] ?: return@mapNotNull null
            ExpandedMacro(
                filePattern = ref.whenFilesMatch,
                rules = macro.rules,
                macroName = ref.useMacro,
            )
        }

    /**
     * Returns rule overrides applicable to [filePath] by checking each expanded macro's patterns.
     *
     * Patterns are [GlobMatcher] path globs matched against the whole path: `**` matches any path segments, `*`
     * matches within a single segment. Later macros override earlier ones for the same rule key.
     *
     * @param filePath The file path to match against macro patterns.
     * @param expandedMacros The expanded macros to check.
     * @return Merged map of rule key to config override element.
     */
    fun findApplicableRules(
        filePath: String,
        expandedMacros: List<ExpandedMacro>,
    ): Map<String, JsonElement> {
        val result = mutableMapOf<String, JsonElement>()
        for (macro in expandedMacros) {
            for (pattern in macro.filePattern) {
                if (GlobMatcher.matches(filePath, pattern)) {
                    result.putAll(macro.rules)
                }
            }
        }
        return result
    }
}

/**
 * A named collection of rule config overrides that can be referenced by [MacroReference].
 *
 * @param rules Map of rule ID to its config override as a [JsonElement].
 */
@Serializable
data class MacroDefinition(
    @PreserveKeys
    val rules: Map<String, JsonElement> = emptyMap(),
)

/**
 * A reference to a named macro that applies when files match specified patterns.
 *
 * @param whenFilesMatch Glob patterns; the macro applies when any pattern matches the file path.
 * @param useMacro Name of the macro to apply (must exist in the macros definition map).
 */
@Serializable
data class MacroReference(
    val whenFilesMatch: List<String> = emptyList(),
    val useMacro: String,
)

/**
 * An expanded macro ready for file-path matching.
 *
 * @param filePattern Glob patterns this macro applies to.
 * @param rules Rule config overrides to apply when a file matches.
 * @param macroName The name of the macro this was expanded from.
 */
@Serializable
data class ExpandedMacro(
    val filePattern: List<String>,
    val rules: Map<String, JsonElement>,
    val macroName: String,
)
