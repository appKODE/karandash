package io.karandash.plugins.ai.dispatch

import io.karandash.models.FileChange
import io.karandash.utils.GlobMatcher

/**
 * Trivial-diff detection: an MR whose every changed file is whitespace-only, a lockfile, generated
 * (matches the noise `exclude` patterns) or docs gets no AI review.
 *
 * Lockfiles, generated files and docs are told by name, so a diff the platform left out does not matter. A file
 * that needs its diff to be trivial and has none is not (the platform may have dropped a large real change), nor is
 * an empty MR.
 */
internal object TrivialDiff {
    const val REASON_PREFIX = "trivial diff: "

    /** Kinds in reporting order; each file takes the first kind it matches. */
    private const val WHITESPACE = "whitespace-only"
    private const val LOCKFILE = "lockfile"
    private const val GENERATED = "generated"
    private const val DOCS = "docs-only"
    private val ORDER = listOf(WHITESPACE, LOCKFILE, GENERATED, DOCS)

    private val LOCKFILES = setOf(
        "package-lock.json",
        "yarn.lock",
        "pnpm-lock.yaml",
        "bun.lockb",
        "npm-shrinkwrap.json",
        "gradle.lockfile",
        "Gemfile.lock",
        "Cargo.lock",
        "poetry.lock",
        "Pipfile.lock",
        "uv.lock",
        "composer.lock",
        "go.sum",
        "Podfile.lock",
        "Package.resolved",
        "pubspec.lock",
        "mix.lock",
        "flake.lock",
        "packages.lock.json",
    )
    private val DOC_EXTENSIONS = setOf("md", "mdx", "markdown", "rst", "adoc", "asciidoc")

    /** Files where indentation is meaning, so a reindent is never whitespace-only. */
    private val INDENT_EXTENSIONS = setOf("py", "yaml", "yml", "mk")
    private val INDENT_NAMES = setOf("Makefile", "GNUmakefile", "makefile")

    /** `trivial diff: <kinds>` when every file is trivial, else null. */
    fun reason(
        changes: List<FileChange>,
        generatedPatterns: List<String>,
    ): String? {
        if (changes.isEmpty()) return null
        val kinds = changes.map { kind(it, generatedPatterns) ?: return null }.toSet()
        return REASON_PREFIX + ORDER.filter { it in kinds }.joinToString(", ")
    }

    private fun kind(
        change: FileChange,
        generatedPatterns: List<String>,
    ): String? {
        val name = change.path.substringAfterLast('/')
        val extension = name.substringAfterLast('.', "").lowercase()
        val diff = change.diffContent
        return when {
            name in LOCKFILES -> LOCKFILE
            generatedPatterns.any { GlobMatcher.matches(change.path, it) } -> GENERATED
            extension in DOC_EXTENSIONS -> DOCS
            diff == null || extension in INDENT_EXTENSIONS || name in INDENT_NAMES -> null
            isWhitespaceOnly(diff) -> WHITESPACE
            else -> null
        }
    }

    /**
     * Added and removed lines, in order, match once each is trimmed and its whitespace runs collapse to one space;
     * blank lines are ignored. A diff without changed lines is not whitespace-only.
     */
    private fun isWhitespaceOnly(diff: String): Boolean {
        val changed = diff.lines().filter { (it.startsWith('+') && !it.startsWith("+++")) || (it.startsWith('-') && !it.startsWith("---")) }
        if (changed.isEmpty()) return false
        val (added, removed) = changed.partition { it.startsWith('+') }
        return squash(added) == squash(removed)
    }

    private fun squash(lines: List<String>): List<String> =
        lines
            .map { line ->
                line
                    .drop(1)
                    .split(' ', '\t', '\r')
                    .filter { it.isNotEmpty() }
                    .joinToString(" ")
            }.filter { it.isNotEmpty() }
}
