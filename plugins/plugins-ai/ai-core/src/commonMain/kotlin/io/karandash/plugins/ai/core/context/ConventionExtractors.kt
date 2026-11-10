package io.karandash.plugins.ai.core.context

import io.karandash.api.support.suspendCatching
import io.karandash.utils.GlobMatcher

/**
 * Per-format extractors that pull ONLY the relevant sections from a convention
 * file. Never returns the whole file - the goal is to give an AI reviewer the
 * rules without wasting token budget on boilerplate.
 *
 * Each extractor is defensive: malformed input produces an empty string, not
 * an exception. Callers are expected to cap the output with `take(maxChars)`.
 */
object ConventionExtractors {
    /** Dispatch to the extractor for [type]. Unknown types get raw content up to [maxChars]. */
    fun extract(
        content: String,
        type: String,
        changedPaths: List<String>,
        maxChars: Int,
    ): String {
        if (content.isBlank() || maxChars <= 0) return ""
        val extracted = when (type) {
            TYPE_CODE_STYLE -> extractEditorConfig(content)
            TYPE_OWNERSHIP -> extractCodeOwners(content, changedPaths)
            TYPE_CONTRIBUTING, TYPE_REVIEW_GUIDELINES -> extractContributing(content, maxChars)
            TYPE_CUSTOM, TYPE_AGENT_RULES -> extractMarkdown(content, maxChars)
            TYPE_LINTER -> extractLinterRules(content)
            TYPE_PR_TEMPLATE -> extractPrTemplate(content, maxChars)
            else -> content
        }
        return extracted.take(maxChars)
    }

    /** `.editorconfig` → comma-separated list of the interesting rules. */
    fun extractEditorConfig(content: String): String {
        val rules = mutableMapOf<String, String>()
        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#') || trimmed.startsWith('[') || '=' !in trimmed) continue
            val parts = trimmed.split('=', limit = 2)
            if (parts.size < 2) continue
            val key = parts[0].trim()
            val value = parts[1].trim()
            if (key in EDITOR_CONFIG_KEYS) rules[key] = value
        }
        return if (rules.isEmpty()) "" else rules.entries.joinToString(", ") { "${it.key}=${it.value}" }
    }

    /** CODEOWNERS → rules whose pattern matches at least one changed path. */
    fun extractCodeOwners(
        content: String,
        changedPaths: List<String>
    ): String {
        if (changedPaths.isEmpty()) return ""
        val matched = linkedSetOf<String>()
        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
            val parts = trimmed.split("\\s+".toRegex())
            if (parts.size < 2) continue
            val pattern = parts[0]
            val owners = parts.drop(1).joinToString(", ")
            val isMatch = changedPaths.any { path ->
                suspendCatching { GlobMatcher.codeownersMatches(path, pattern) }.getOrDefault(false)
            }
            if (isMatch) matched += "$pattern -> $owners"
        }
        return matched.joinToString("\n")
    }

    /** CONTRIBUTING.md → sections whose heading mentions review/test/PR keywords. */
    fun extractContributing(
        content: String,
        maxChars: Int
    ): String = extractSectionsByKeywords(content, CONTRIBUTING_KEYWORDS, maxChars)

    /** Markdown passthrough for `.karandash/review-guidelines.md` and user-supplied files. */
    fun extractMarkdown(
        content: String,
        maxChars: Int
    ): String = content.take(maxChars)

    /** detekt.yml / .eslintrc → threshold lines only. */
    fun extractLinterRules(content: String): String {
        val rules = mutableListOf<String>()
        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
            if (LINTER_THRESHOLD_KEYS.any { key -> key in trimmed } && ':' in trimmed) {
                rules += trimmed
            }
        }
        return if (rules.isEmpty()) "" else rules.joinToString("\n")
    }

    /** PR template → heading lines + checklist items only (shows expected structure). */
    fun extractPrTemplate(
        content: String,
        maxChars: Int
    ): String {
        val headings = content
            .lines()
            .filter { line ->
                val trimmed = line.trim()
                trimmed.startsWith("#") || trimmed.startsWith("- [ ]") || trimmed.startsWith("- [x]")
            }
        return headings.joinToString("\n").take(maxChars)
    }

    private fun extractSectionsByKeywords(
        content: String,
        keywords: List<String>,
        maxChars: Int
    ): String {
        val sections = mutableListOf<String>()
        val current = StringBuilder()
        var inRelevant = false
        for (line in content.lines()) {
            if (line.startsWith('#')) {
                if (inRelevant && current.isNotBlank()) sections += current.toString()
                current.clear()
                inRelevant = keywords.any { it in line.lowercase() }
            }
            if (inRelevant) current.appendLine(line)
        }
        if (inRelevant && current.isNotBlank()) sections += current.toString()
        return sections.joinToString("\n---\n").take(maxChars)
    }

    // Type identifiers used by [ConventionDetector].
    const val TYPE_CODE_STYLE: String = "code-style"
    const val TYPE_OWNERSHIP: String = "ownership"
    const val TYPE_CONTRIBUTING: String = "contributing"
    const val TYPE_LINTER: String = "linter"
    const val TYPE_REVIEW_GUIDELINES: String = "review-guidelines"
    const val TYPE_PR_TEMPLATE: String = "pr-template"
    const val TYPE_CUSTOM: String = "custom"

    /** AGENTS.md, CLAUDE.md, .cursorrules, .github/copilot-instructions.md: every one found is kept. */
    const val TYPE_AGENT_RULES: String = "agent-rules"

    private val EDITOR_CONFIG_KEYS: Set<String> = setOf(
        "indent_style",
        "indent_size",
        "max_line_length",
        "end_of_line",
        "charset",
        "tab_width",
        "trim_trailing_whitespace",
    )

    private val CONTRIBUTING_KEYWORDS: List<String> = listOf(
        "review",
        "style",
        "test",
        "pr",
        "pull request",
        "commit",
        "coding",
        "standard",
        "convention",
    )

    private val LINTER_THRESHOLD_KEYS: List<String> = listOf(
        "maxLineLength",
        "max_line_length",
        "maxClassSize",
        "LongMethod",
        "LargeClass",
        "CyclomaticComplexMethod",
        "LongParameterList",
        "maxMethodLength",
        "maxBodyLength",
        "max-len",
        "max-lines",
        "complexity",
    )
}
