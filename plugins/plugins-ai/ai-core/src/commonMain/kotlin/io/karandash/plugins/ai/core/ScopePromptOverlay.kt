package io.karandash.plugins.ai.core

import io.karandash.models.FileChange
import io.karandash.patterns.TextGlobCompiler
import io.karandash.sdk.content.ContentSource
import io.karandash.utils.GlobExpander
import io.karandash.utils.GlobMatcher
import kotlinx.serialization.Serializable

/**
 * A named scope that matches files by glob patterns and contributes
 * additional instructions and best practices to AI prompts.
 *
 * @param name Scope identifier (e.g., "android", "ios", "core").
 * @param paths Glob patterns that trigger this scope when matched.
 * @param instructions Scope-specific review instructions appended to AI prompts.
 * @param bestPractices Scope-specific best practices appended to AI prompts.
 */
@Serializable
data class ScopeDefinition(
    val name: String,
    val paths: List<String>,
    val instructions: ContentSource? = null,
    val bestPractices: ContentSource? = null,
)

/**
 * A path-scoped project review instruction (`ai.review.instructions`, or an entry of
 * `.gitlab/duo/mr-review-instructions.yaml`). It reaches the prompt, whole, only when one of [paths] matches a
 * changed file; a path starting with `!` excludes the files it matches.
 */
@Serializable
data class ReviewInstruction(
    val name: String,
    val paths: List<String>,
    val text: String,
)

/**
 * `ai.review`: project review settings. [instructions] come from the config, which follows the config trust model
 * (the run's own config chain). [duoInstructionsFile] adds `.gitlab/duo/mr-review-instructions.yaml` as read from the
 * MR target branch, never the MR head; off by default.
 */
@Serializable
data class ReviewConfig(
    val instructions: List<ReviewInstruction> = emptyList(),
    val duoInstructionsFile: Boolean = false,
    /** Append the bug-class checklist (and one line for the MR's main language) to the default review prompt. */
    val bugChecklist: Boolean = true,
)

/**
 * Matches file changes against [ScopeDefinition] path patterns and builds
 * combined prompt instructions from all matching scopes.
 */
object ScopeMatcher {
    private val globDialect = TextGlobCompiler()

    /**
     * Returns all scopes that have at least one file matching their glob patterns.
     *
     * @param changes File changes from the merge request.
     * @param scopes Configured scope definitions.
     * @return Scopes with at least one matching file, preserving config order.
     */
    fun matchScopes(
        changes: List<FileChange>,
        scopes: List<ScopeDefinition>,
    ): List<ScopeDefinition> {
        if (scopes.isEmpty() || changes.isEmpty()) return emptyList()
        return scopes.filter { scope ->
            scope.paths.any { pattern ->
                changes.any { change -> pathMatches(change.path, pattern) }
            }
        }
    }

    /**
     * Builds a combined instructions block from matched scopes.
     *
     * Each scope contributes a `## Scope: {name}` section with its instructions
     * and/or best practices. Returns null if no scopes have content.
     *
     * @param matched Scopes that matched the MR's file changes.
     * @return Combined scope instructions, or null if empty.
     */
    fun buildScopeInstructions(matched: List<ScopeDefinition>): String? {
        if (matched.isEmpty()) return null
        val sections = matched.mapNotNull { scope ->
            val parts = mutableListOf<String>()
            scope.instructions?.inlineValueOrNull()?.let { parts.add(it) }
            scope.bestPractices?.inlineValueOrNull()?.let { parts.add("Best practices:\n$it") }
            if (parts.isEmpty()) return@mapNotNull null
            "## Scope: ${scope.name}\n${parts.joinToString("\n\n")}"
        }
        return sections.joinToString("\n\n").ifEmpty { null }
    }

    /**
     * The [instructions] with at least one changed file among their paths, in config order. Paths that are all
     * `!`-excludes stand for every file but those.
     */
    fun matchInstructions(
        changes: List<FileChange>,
        instructions: List<ReviewInstruction>,
    ): List<ReviewInstruction> =
        instructions.filter { instruction ->
            // `!` excludes are read by the glob dialect's own negation helpers
            val (excluded, included) = instruction.paths.partition(globDialect::isNegation)
            val everyFile = included.isEmpty() && excluded.isNotEmpty()
            changes.any { change ->
                (everyFile || included.any { pathMatches(change.path, it) }) &&
                    excluded.none { pathMatches(change.path, globDialect.stripNegation(it)) }
            }
        }

    /** One `## Review instruction: {name}` section per [matched] instruction, text whole; null when none. */
    fun buildInstructionBlock(matched: List<ReviewInstruction>): String? =
        matched.joinToString("\n\n") { "## Review instruction: ${it.name}\n${it.text}" }.ifEmpty { null }

    /**
     * scope paths are file globs; the `glob:` prefix used elsewhere in the config is accepted and dropped.
     * Brace groups expand first (`{src,lib}/**/*.{kt,java}` is four globs), as `docs/reference/patterns.md` says.
     */
    private fun pathMatches(
        path: String,
        pattern: String,
    ): Boolean = GlobExpander.expandBraces(pattern.removePrefix(GLOB_PREFIX)).any { GlobMatcher.matches(path, it) }

    private const val GLOB_PREFIX = "glob:"

    private fun ContentSource.inlineValueOrNull(): String? = if (this is ContentSource.Inline) value else null
}
