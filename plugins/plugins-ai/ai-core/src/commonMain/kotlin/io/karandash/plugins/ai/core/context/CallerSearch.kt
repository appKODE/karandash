package io.karandash.plugins.ai.core.context

import io.karandash.models.FileChange
import io.karandash.platform.extension
import io.karandash.platform.isDirectory
import io.karandash.platform.listFiles
import io.karandash.platform.readText
import io.karandash.platform.safeName
import io.karandash.plugins.ai.core.CrossFileContextBuilder
import io.karandash.plugins.ai.core.SensitiveFileFilter
import io.karandash.security.TextRedactor
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * `relatedFiles.sources.callers`: call sites of the symbols a diff declares, changes or removes, found by a text search
 * of the working tree. At most [PER_SYMBOL] one-line snippets per symbol, [MAX_SYMBOLS] symbols, within `maxChars`.
 *
 * limit: a plain text match of `name(` over source files, not a reference index. Overloads, same-named symbols in
 * other modules and dynamic calls give false or missed hits, and a big tree is cut at [MAX_FILES] files. Swap in an
 * LSP or tree-sitter reference lookup if precision matters.
 */
internal object CallerSearch {
    private val NAMED = Regex("""\b(?:fun|def|function|func|fn|class|interface|object|struct|enum)\s+(?:<[^>]*>\s*)?(?:\w+\.)*(\w+)""")
    private val CALL_NAME = Regex("""(\w+)\s*\(""")
    private const val INTRO = "Call sites of changed symbols (text search of the working tree):"

    /** Names declared on the changed (`+`) and removed (`-`) lines of [changes]. */
    fun symbols(changes: List<FileChange>): List<String> =
        changes
            .flatMap { change ->
                val changed = change.diffContent
                    .orEmpty()
                    .lineSequence()
                    .filter { (it.startsWith('+') && !it.startsWith("+++")) || (it.startsWith('-') && !it.startsWith("---")) }
                    .joinToString("\n") { "+${it.drop(1)}" }
                val cFamily = CrossFileContextBuilder.isCFamily(change.path)
                CrossFileContextBuilder.extractSignatures(changed, cFamily).mapNotNull { signature ->
                    (NAMED.find(signature) ?: CALL_NAME.find(signature))?.groupValues?.get(1)
                }
            }.filter { it.length >= MIN_NAME && it !in KEYWORDS }
            .distinct()
            .take(MAX_SYMBOLS)

    /**
     * The call sites of [symbols] in the source files under [root], skipping [skip] (the changed files) and sensitive
     * files, or `null` when there are none. [warn] gets one line per file or directory that could not be read.
     */
    fun search(
        root: String,
        symbols: List<String>,
        skip: Set<String>,
        maxChars: Int,
        warn: (String) -> Unit,
    ): String? {
        if (root.isBlank() || symbols.isEmpty() || maxChars <= INTRO.length) return null
        val hits = symbols.associateWith { mutableListOf<String>() }
        var bytes = 0L
        for (rel in sourceFiles(root, warn)) {
            if (hits.values.all { it.size >= PER_SYMBOL } || bytes > MAX_TOTAL_BYTES) break
            if (rel in skip || SensitiveFileFilter.isSensitive(rel)) continue
            val text = read(Path(root, rel), warn) ?: continue
            bytes += text.length
            text.lineSequence().forEachIndexed { i, line ->
                symbols
                    .filter { hits.getValue(it).size < PER_SYMBOL && calls(line, it) }
                    .forEach { hits.getValue(it) += "$rel:${i + 1}: ${line.trim().take(MAX_LINE_CHARS)}" }
            }
        }
        return render(hits, maxChars)
    }

    private fun render(
        hits: Map<String, List<String>>,
        maxChars: Int,
    ): String? {
        val out = StringBuilder(INTRO)
        for ((symbol, sites) in hits) {
            if (sites.isEmpty()) continue
            val section = "\n  $symbol:" + sites.joinToString("") { "\n    - ${TextRedactor.redact(it)}" }
            if (out.length + section.length > maxChars) break
            out.append(section)
        }
        return out.toString().takeIf { it.length > INTRO.length }
    }

    /** True when [symbol] appears in [line] as a whole word followed by `(`, not right after a declaring keyword. */
    internal fun calls(
        line: String,
        symbol: String,
    ): Boolean {
        var at = line.indexOf(symbol)
        while (at >= 0) {
            val before = line.getOrNull(at - 1)
            val after = line.substring(at + symbol.length).trimStart().firstOrNull()
            val declared = line.substring(0, at).trimEnd().substringAfterLast(' ') in DECLARING
            if ((before == null || !before.isIdentifierPart()) && after == '(' && !declared) return true
            at = line.indexOf(symbol, at + 1)
        }
        return false
    }

    private fun Char.isIdentifierPart(): Boolean = isLetterOrDigit() || this == '_'

    /** Repository-relative source files, shallow first, skipping hidden and build directories. */
    private fun sourceFiles(
        root: String,
        warn: (String) -> Unit,
    ): Sequence<String> =
        sequence {
            val realRoot = resolve(Path(root), warn) ?: root
            val dirs = ArrayDeque(listOf(""))
            var entries = 0
            var files = 0
            while (dirs.isNotEmpty() && entries < MAX_ENTRIES && files < MAX_FILES) {
                val dir = dirs.removeFirst()
                for (entry in list(if (dir.isEmpty()) Path(root) else Path(root, dir), warn)) {
                    entries++
                    val rel = if (dir.isEmpty()) entry.safeName else "$dir/${entry.safeName}"
                    if (entry.safeName.startsWith('.') || entry.safeName in SKIP_DIRS) continue
                    if (entry.isDirectory()) {
                        // a symlinked directory pointing outside the checkout is not walked
                        if (resolve(entry, warn)?.startsWith("$realRoot/") == true) dirs.addLast(rel)
                    } else if (entry.extension() in SOURCE_EXTENSIONS && size(entry, warn) <= MAX_FILE_BYTES) {
                        files++
                        yield(rel)
                    }
                }
            }
        }

    /** The size of [path], or [Long.MAX_VALUE] (skipped) when its metadata cannot be read. */
    private fun size(
        path: Path,
        warn: (String) -> Unit,
    ): Long =
        try {
            SystemFileSystem.metadataOrNull(path)?.size ?: 0
        } catch (e: IOException) {
            warn("Related files: cannot stat $path for the callers search: ${e.message}")
            Long.MAX_VALUE
        }

    /** The canonical [path], or `null` when it cannot be resolved (a broken symlink). */
    private fun resolve(
        path: Path,
        warn: (String) -> Unit,
    ): String? =
        try {
            SystemFileSystem.resolve(path).toString()
        } catch (e: IOException) {
            warn("Related files: cannot resolve $path for the callers search: ${e.message}")
            null
        }

    private fun list(
        dir: Path,
        warn: (String) -> Unit,
    ): List<Path> =
        try {
            dir.listFiles().orEmpty().sortedBy { it.safeName }
        } catch (e: IOException) {
            warn("Related files: cannot list $dir for the callers search: ${e.message}")
            emptyList()
        }

    private fun read(
        path: Path,
        warn: (String) -> Unit,
    ): String? =
        try {
            path.readText()
        } catch (e: IOException) {
            warn("Related files: cannot read $path for the callers search: ${e.message}")
            null
        }

    private const val MIN_NAME = 3
    private const val MAX_SYMBOLS = 20
    private const val PER_SYMBOL = 5
    private const val MAX_LINE_CHARS = 160
    private const val MAX_FILES = 5_000
    private const val MAX_ENTRIES = 50_000
    private const val MAX_FILE_BYTES = 512L * 1024
    private const val MAX_TOTAL_BYTES = 16L * 1024 * 1024
    private val DECLARING = setOf("fun", "def", "func", "function", "fn", "void")
    private val KEYWORDS = setOf("fun", "def", "func", "function", "for", "while", "switch", "return", "catch", "main")
    private val SKIP_DIRS = setOf("build", "node_modules", "target", "out", "dist", "vendor", "__pycache__")
    private val SOURCE_EXTENSIONS = setOf(
        "kt",
        "kts",
        "java",
        "scala",
        "groovy",
        "py",
        "js",
        "jsx",
        "ts",
        "tsx",
        "go",
        "rs",
        "rb",
        "php",
        "cs",
        "swift",
        "m",
        "mm",
        "c",
        "h",
        "cc",
        "cpp",
        "cxx",
        "hh",
        "hpp",
        "hxx",
    )
}
