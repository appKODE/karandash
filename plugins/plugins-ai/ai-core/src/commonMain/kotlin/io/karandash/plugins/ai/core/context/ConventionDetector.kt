package io.karandash.plugins.ai.core.context

import io.karandash.api.support.suspendCatching
import io.karandash.platform.GitArgSanitizer
import kotlinx.serialization.Serializable

/**
 * Collects project convention files (known locations + user-configured extras)
 * and hands each to [ConventionExtractors] for per-type distillation.
 *
 * Scanned locations, in priority order (highest first):
 *   400  user-supplied via config
 *   300  .karandash/review-guidelines.md
 *   275  AGENTS.md / CLAUDE.md / .cursorrules / .github/copilot-instructions.md (all kept, not deduplicated)
 *   250  CONTRIBUTING.md
 *   200  .editorconfig
 *   150  CODEOWNERS
 *   100  detekt.yml / .detekt.yml / detekt/detekt.yml / .eslintrc.{json,yml,js}
 *    50  .github/PULL_REQUEST_TEMPLATE.md / .gitlab/merge_request_templates/Default.md
 *
 * Deduplicated by [ConventionSource.type] - first wins, custom files go first
 * so user entries always override the built-in defaults; custom and agent files are never deduplicated.
 *
 * File content is read via the supplied [FileReader] callback; in production it looks the path up among
 * the files read from the MR base branch.
 */
object ConventionDetector {
    /** File-reader function. Returns null when the file doesn't exist or is unreadable. */
    fun interface FileReader {
        suspend fun read(path: String): String?
    }

    /** Priority-anchored convention source. */
    @Serializable
    data class ConventionSource(
        val path: String,
        val type: String,
        val priority: Int,
    )

    /** Result per detected file. */
    @Serializable
    data class DetectedConvention(
        val path: String,
        val type: String,
        val content: String,
        val priority: Int,
    )

    /** Well-known convention files in priority order. */
    val KNOWN_SOURCES: List<ConventionSource> = listOf(
        ConventionSource(".karandash/review-guidelines.md", ConventionExtractors.TYPE_REVIEW_GUIDELINES, 300),
        ConventionSource("AGENTS.md", ConventionExtractors.TYPE_AGENT_RULES, AGENT_RULES_PRIORITY),
        ConventionSource("CLAUDE.md", ConventionExtractors.TYPE_AGENT_RULES, AGENT_RULES_PRIORITY),
        ConventionSource(".cursorrules", ConventionExtractors.TYPE_AGENT_RULES, AGENT_RULES_PRIORITY),
        ConventionSource(".github/copilot-instructions.md", ConventionExtractors.TYPE_AGENT_RULES, AGENT_RULES_PRIORITY),
        ConventionSource("CONTRIBUTING.md", ConventionExtractors.TYPE_CONTRIBUTING, 250),
        ConventionSource(".editorconfig", ConventionExtractors.TYPE_CODE_STYLE, 200),
        ConventionSource("CODEOWNERS", ConventionExtractors.TYPE_OWNERSHIP, 150),
        ConventionSource(".github/CODEOWNERS", ConventionExtractors.TYPE_OWNERSHIP, 150),
        ConventionSource(".gitlab/CODEOWNERS", ConventionExtractors.TYPE_OWNERSHIP, 150),
        ConventionSource("detekt.yml", ConventionExtractors.TYPE_LINTER, 100),
        ConventionSource(".detekt.yml", ConventionExtractors.TYPE_LINTER, 100),
        ConventionSource("detekt/detekt.yml", ConventionExtractors.TYPE_LINTER, 100),
        ConventionSource(".eslintrc.json", ConventionExtractors.TYPE_LINTER, 100),
        ConventionSource(".eslintrc.yml", ConventionExtractors.TYPE_LINTER, 100),
        ConventionSource(".eslintrc.js", ConventionExtractors.TYPE_LINTER, 100),
        ConventionSource(".github/PULL_REQUEST_TEMPLATE.md", ConventionExtractors.TYPE_PR_TEMPLATE, 50),
        ConventionSource(".gitlab/merge_request_templates/Default.md", ConventionExtractors.TYPE_PR_TEMPLATE, 50),
    )

    /**
     * @param workingDir Repository root (used only to enforce path containment
     *     via [GitArgSanitizer.validateWithinRepo] on user paths).
     * @param customFiles Additional user-configured paths. Must be repo-relative.
     *     Assigned priorities 400, 399, 398 … so they rank above built-ins.
     * @param changedPaths Paths of changed files - passed to
     *     [ConventionExtractors.extractCodeOwners] for per-path filtering.
     * @param maxChars Total character budget shared across every detected file. Conventions are prompt
     *     tier T0, so the default is no cap; under a cap a file that does not fit whole is skipped, never cut.
     * @param reader Pluggable file reader. Tests pass an in-memory map; the
     *     production caller wraps `git show HEAD:<path>`.
     */
    suspend fun detect(
        workingDir: String,
        customFiles: List<String> = emptyList(),
        changedPaths: List<String> = emptyList(),
        maxChars: Int = Int.MAX_VALUE,
        reader: FileReader,
    ): List<DetectedConvention> {
        if (maxChars <= 0) return emptyList()

        val custom = customFiles.mapIndexedNotNull { idx, path ->
            val safe = suspendCatching { GitArgSanitizer.validateWithinRepo(path, workingDir) }.getOrNull()
                ?: return@mapIndexedNotNull null
            ConventionSource(path = safe, type = ConventionExtractors.TYPE_CUSTOM, priority = CUSTOM_BASE_PRIORITY - idx)
        }

        val sources = (custom + KNOWN_SOURCES).sortedByDescending { it.priority }
        val seenTypes = mutableSetOf<String>()
        val result = mutableListOf<DetectedConvention>()
        var used = 0
        for (source in sources) {
            if (used >= maxChars) break
            if (source.type !in UNDEDUPLICATED_TYPES && source.type in seenTypes) continue

            val content = reader.read(source.path) ?: continue
            val extracted = ConventionExtractors.extract(
                content = content,
                type = source.type,
                changedPaths = changedPaths,
                maxChars = Int.MAX_VALUE,
            )
            if (extracted.isBlank() || extracted.length > maxChars - used) continue

            result += DetectedConvention(source.path, source.type, extracted, source.priority)
            used += extracted.length
            seenTypes += source.type
        }
        return result
    }

    private const val CUSTOM_BASE_PRIORITY: Int = 400
    private const val AGENT_RULES_PRIORITY: Int = 275
    private val UNDEDUPLICATED_TYPES = setOf(ConventionExtractors.TYPE_CUSTOM, ConventionExtractors.TYPE_AGENT_RULES)
}
