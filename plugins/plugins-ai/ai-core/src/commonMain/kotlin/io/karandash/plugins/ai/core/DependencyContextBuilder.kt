package io.karandash.plugins.ai.core

import io.karandash.models.FileChange
import io.karandash.platform.PathSecurity
import io.karandash.plugins.ai.core.config.CrossFileContextConfig
import io.karandash.plugins.ai.core.context.SymbolDefinitions
import io.karandash.plugins.ai.core.context.testCandidates
import io.karandash.security.TextRedactor

/**
 * `ai.crossFileContext`: the "Context from Dependencies" prompt section. Resolves the changed files'
 * imports to repository files (Kotlin/Java FQNs under `src/<set>/<kotlin|java>/`, relative TS/JS
 * imports, Python modules, C/C++ `#include`s) and adds the matching test files, then sends each file's declarations
 * (or full text) through [TextRedactor]. Files [SensitiveFileFilter] flags are never read.
 *
 * limit: import resolution is path-heuristic, not a compiler model; wildcard and aliased imports are skipped.
 */
object DependencyContextBuilder {
    const val HEADING = "## Context from Dependencies"

    /** limit: caps platform lookups per rule; a JVM import fans out to several candidate paths. */
    private const val MAX_LOOKUPS = 40

    private val JVM_SOURCE_ROOT = Regex("""^(.*?src/[^/]+/(?:kotlin|java)/)""")
    private val JVM_PACKAGE = Regex("""^\+?\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
    private val JVM_EXTENSIONS = listOf("kt", "java")

    /** Leading package segments that name the project (`com.acme`), not a module. */
    private const val PROJECT_PACKAGE_DEPTH = 2

    /** limit: source roots tried per JVM import, so one unresolvable import cannot spend every lookup. */
    private const val MAX_ROOTS_PER_IMPORT = 3

    /** Package segments a guessed module path may take (`core/data/network`). */
    private const val MAX_GUESS_DEPTH = 3
    private val JS_EXTENSIONS = listOf("ts", "tsx", "js", "jsx")

    /**
     * @param readFile Returns a repository-relative file's text, or `null` when it does not exist.
     * @return The section, or `null` when no related file was found.
     */
    fun build(
        changes: List<FileChange>,
        config: CrossFileContextConfig,
        maxChars: Int,
        readFile: (String) -> String?,
    ): String? {
        if (maxChars <= 0 || config.maxContextFiles <= 0) return null
        val related = readableCandidates(changes, config)
            .mapNotNull { path -> readFile(path)?.let { path to it } }
            .take(config.maxContextFiles)
            .toList()
        if (related.isEmpty()) return null
        val body = related.joinToString("\n\n") { (path, text) ->
            val content = if (config.captureSignatures) signatures(path, text) else text
            "### $path\n${TextRedactor.redact(content)}"
        }
        return "$HEADING\n$body".take(maxChars)
    }

    /**
     * Reads related files through a suspending [readFile] (a platform lookup when the repository is not checked
     * out), stopping at `maxContextFiles` hits or [MAX_LOOKUPS] lookups; pass the result to [build] as its reader.
     */
    suspend fun fetchRelated(
        changes: List<FileChange>,
        config: CrossFileContextConfig,
        readFile: suspend (String) -> String?,
    ): Map<String, String> {
        val found = linkedMapOf<String, String>()
        for (path in readableCandidates(changes, config).take(MAX_LOOKUPS)) {
            if (found.size >= config.maxContextFiles) break
            readFile(path)?.let { found[path] = it }
        }
        return found
    }

    private fun readableCandidates(
        changes: List<FileChange>,
        config: CrossFileContextConfig,
    ): Sequence<String> {
        val changed = changes.map { it.path }.toSet()
        return relatedPaths(changes, config).asSequence().filter { it !in changed && !SensitiveFileFilter.isSensitive(it) }
    }

    /**
     * Candidate paths in priority order: resolved imports of the changed files, then their tests. An import whose
     * name is used on a changed line comes first, so the `maxContextFiles` cap keeps what the change calls. Imports
     * come from the full file when the CLI fetched it, so an `#include` outside the hunks still counts; else from the diff.
     */
    internal fun relatedPaths(
        changes: List<FileChange>,
        config: CrossFileContextConfig,
    ): List<String> {
        val roots = changes.mapNotNull { JVM_SOURCE_ROOT.find(it.path)?.groupValues?.get(1) }.distinct()
        val imports = if (config.includeImports) {
            val used = SymbolDefinitions.usedNames(changes).toSet()
            val project = projectPackages(changes)
            val bases = moduleBases(changes)
            changes
                .flatMap { change ->
                    CrossFileContextBuilder.extractImports(change.newContent ?: change.diffContent.orEmpty()).map { change.path to it }
                }.filter { (path, import) -> isProjectImport(path, import, project) }
                .sortedBy { (_, import) -> if (importedName(import) in used) 0 else 1 }
                .flatMap { (path, import) -> importCandidates(path, import, roots, bases) }
        } else {
            emptyList()
        }
        val tests = if (config.includeTests) changes.flatMap { testCandidates(it.path) } else emptyList()
        return (imports + tests).distinct()
    }

    /** The first two segments (`com.acme`) of each changed Kotlin/Java file's package. */
    private fun projectPackages(changes: List<FileChange>): Set<String> =
        changes
            .filter { it.path.substringAfterLast('.') in JVM_EXTENSIONS }
            .mapNotNull { change ->
                val declared = JVM_PACKAGE.find(change.newContent ?: change.diffContent.orEmpty())?.groupValues?.get(1)
                val fromPath = JVM_SOURCE_ROOT.find(change.path)?.let {
                    change.path
                        .removePrefix(it.value)
                        .substringBeforeLast('/', "")
                        .replace('/', '.')
                }
                (declared ?: fromPath)
                    ?.split('.')
                    ?.take(PROJECT_PACKAGE_DEPTH)
                    ?.joinToString(".")
                    ?.takeIf { it.isNotEmpty() }
            }.toSet()

    /**
     * False for an import that cannot be a repository file, so it never spends a lookup: a Kotlin/Java import outside
     * the [project] packages (a library such as `kotlinx.*` or `io.ktor.*`) and an extensionless `#include` (`<vector>`).
     */
    private fun isProjectImport(
        fromPath: String,
        import: String,
        project: Set<String>,
    ): Boolean =
        when {
            fromPath.substringAfterLast('.') in JVM_EXTENSIONS -> project.isEmpty() || project.any { import.startsWith("$it.") }
            CrossFileContextBuilder.isCFamily(fromPath) -> '.' in import.substringAfterLast('/')
            else -> true
        }

    internal fun importCandidates(
        fromPath: String,
        import: String,
        jvmRoots: List<String>,
        moduleBases: List<String> = emptyList(),
    ): List<String> {
        val dir = fromPath.substringBeforeLast('/', "")
        val ext = fromPath.substringAfterLast('.', "")
        return when {
            ext in JS_EXTENSIONS && import.startsWith(".") -> {
                val base = resolve(dir, import)
                JS_EXTENSIONS.map { "$base.$it" } + JS_EXTENSIONS.map { "$base/index.$it" }
            }

            ext == "py" -> {
                val module = if (import.startsWith(".")) resolve(dir, import.trimStart('.').replace('.', '/')) else import.replace('.', '/')
                listOf("$module.py", "$module/__init__.py")
            }

            // `#include "x.h"`: next to the including file, then from the repository root.
            CrossFileContextBuilder.isCFamily(fromPath) -> {
                listOf(resolve(dir, import), import)
            }

            ext in JVM_EXTENSIONS -> {
                val segments = import.split('.')
                // `a.b.C`, `a.b.C.Nested` and `a.b.C.member` live in `C`'s file; a top-level `a.b.function` is guessed at `a/b`.
                val type = segments.indexOfFirst { it.firstOrNull()?.isUpperCase() == true }
                val parts = if (type >= 0) segments.take(type + 1) else segments.dropLast(1)
                val relative = parts.joinToString("/")
                if (parts.isEmpty()) {
                    emptyList()
                } else {
                    rootsFor(parts.dropLast(1), jvmRoots, moduleBases).flatMap { root ->
                        (if (root.endsWith("/kotlin/")) listOf("kt") else listOf("java", "kt")).map { "$root$relative.$it" }
                    }
                }
            }

            else -> {
                emptyList()
            }
        }
    }

    /**
     * The [MAX_ROOTS_PER_IMPORT] source roots most likely to hold package [pkg]: the changed files' [known] roots plus,
     * under each module [bases] dir, a module named by the package (`com.acme.core.data` → `android/core/data/`, the
     * multi-module convention), so an import from an unchanged module resolves too. A root whose module path ends with
     * a longer run of the package's module segments ranks first; then a known root, then a production (`main`, `commonMain`) source set.
     */
    private fun rootsFor(
        pkg: List<String>,
        known: List<String>,
        bases: List<String>,
    ): List<String> {
        val rest = pkg.drop(PROJECT_PACKAGE_DEPTH)
        val layouts = known.map { it.removePrefix(moduleOf(it)) }.filter(::isMain).distinct()
        val guesses = bases.flatMap { base ->
            (1..minOf(rest.size, MAX_GUESS_DEPTH)).flatMap { depth ->
                layouts.map { layout -> base + rest.take(depth).joinToString("") { "$it/" } + layout }
            }
        }
        return (known + guesses)
            .distinct()
            .sortedByDescending { root ->
                matchedDepth(moduleOf(root), rest) * 4 + (if (root in known) 2 else 0) + (if (isMain(root)) 1 else 0)
            }.take(MAX_ROOTS_PER_IMPORT)
    }

    /** How many leading [segments] the [module] dir ends with: 2 for `android/core/data/` and `core.data.network`. */
    private fun matchedDepth(
        module: String,
        segments: List<String>,
    ): Int = (segments.size downTo 1).firstOrNull { "/$module".endsWith(segments.take(it).joinToString("") { s -> "/$s" } + "/") } ?: 0

    /**
     * Dirs that hold modules named by their package: `android/` when `android/feature/app/data/src/main/kotlin/` holds
     * `com.acme.feature.app.data`.
     */
    private fun moduleBases(changes: List<FileChange>): List<String> =
        changes
            .mapNotNull { change ->
                val root = JVM_SOURCE_ROOT.find(change.path)?.groupValues?.get(1) ?: return@mapNotNull null
                val rest = change.path
                    .removePrefix(root)
                    .substringBeforeLast('/', "")
                    .split('/')
                    .drop(PROJECT_PACKAGE_DEPTH)
                val module = moduleOf(root)
                val depth = matchedDepth(module, rest)
                if (depth == 0) null else module.removeSuffix(rest.take(depth).joinToString("") { "$it/" })
            }.distinct()

    /** A production source set: `src/main/` or a KMP `src/commonMain/`, `src/jvmMain/`. */
    private fun isMain(root: String): Boolean = root.substringAfter("src/").substringBefore('/').endsWith("main", ignoreCase = true)

    /** `android/core/data/` of `android/core/data/src/main/kotlin/`. */
    private fun moduleOf(root: String): String = root.substringBefore("src/")

    /** The name an import brings in: `C` of `a.b.C`, `bar` of `./foo/bar` or `foo/bar.h`. */
    private fun importedName(import: String): String {
        val last = import.substringAfterLast('/')
        return (if (CrossFileContextBuilder.isCFamily(last)) last.substringBeforeLast('.') else last).substringAfterLast('.')
    }

    /** Joins and normalizes `dir/relative`, clamped inside the repository root. */
    private fun resolve(
        dir: String,
        relative: String,
    ): String = PathSecurity.normalizePath("/$dir/$relative").removePrefix("/")

    private fun signatures(
        path: String,
        text: String,
    ): String =
        CrossFileContextBuilder
            .extractSignatures(text.lineSequence().joinToString("\n") { "+$it" }, CrossFileContextBuilder.isCFamily(path))
            .joinToString("\n")
}
