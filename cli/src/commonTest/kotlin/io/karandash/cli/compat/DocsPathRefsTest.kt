package io.karandash.cli.compat

import io.karandash.cli.TestFileHelpers
import io.karandash.platform.PlatformProcess
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every repository path in the prose of the docs (`core/config/...`, `cli/.../ConfigLoader.kt:38-113`) and every
 * `File.kt:line` reference names a tracked file (or directory), and its line range lies within the file.
 * A `...` segment matches any run of path characters; paths with `*`, `{` or `<` are patterns and are not checked,
 * nor are build outputs (a `build/` segment). A path that is not a repo path is tried relative to the markdown file.
 */
class DocsPathRefsTest {
    private val tracked: List<String> = gitTrackedFiles()
    private val lineCounts = mutableMapOf<String, Int>()

    @Test
    fun `every repo path and path-line reference in the docs resolves`() {
        val roots = tracked.filter { '/' in it }.map { it.substringBefore('/') }.toSet()
        val pattern = refPattern(roots)
        var checked = 0
        val failures = DocFences.markdownFiles().flatMap { file ->
            val prose = DocFences.prose(TestFileHelpers.readFile(file))
            pattern
                .findAll(prose)
                .mapNotNull { match ->
                    val path = match.groupValues[1].trimEnd('.', ',', ')', ':')
                    val first = match.groupValues[2].toIntOrNull()
                    val last = match.groupValues[3].toIntOrNull() ?: first
                    val skipped = PATTERN_CHARS.any { it in path } || "/build/" in "/$path" || path in USER_REPO_PATHS
                    if (skipped || ('/' !in path && first == null)) return@mapNotNull null
                    checked++
                    val line = prose.substring(0, match.range.first).count { it == '\n' } + 1
                    problem(path, last, file.parent?.toString().orEmpty())?.let { "$file:$line: ${match.value} $it" }
                }.toList()
        }
        assertTrue(checked >= MIN_REFS, "expected at least $MIN_REFS path references, got $checked")
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }

    private fun problem(
        path: String,
        lastLine: Int?,
        docDir: String,
    ): String? {
        val candidates = candidates(path.removePrefix("/")).ifEmpty { candidates(relativeTo(docDir, path)) }
        return when {
            candidates.isEmpty() -> "does not exist"

            lastLine == null -> null

            candidates.none {
                lineCount(
                    it
                ) >= lastLine
            } -> "line $lastLine is past the end (${candidates.map { "$it: ${lineCount(it)}" }})"

            else -> null
        }
    }

    /** Tracked files [path] names: the file itself, files under it as a directory, or by base name when it has no `/`. */
    private fun candidates(path: String): List<String> {
        val dir = path.trimEnd('/')
        val exact = if ("..." in path) {
            val regex = Regex(path.split("...").joinToString("[^ ]*") { Regex.escape(it) } + if (path.endsWith('/')) ".+" else "")
            tracked.filter { regex.matches(it) }
        } else {
            tracked.filter { it == dir || ('/' !in dir && it.endsWith("/$dir")) }
        }
        return exact.ifEmpty { if (tracked.any { it.startsWith("$dir/") }) listOf(dir) else emptyList() }
    }

    /** [path] as a link relative to the markdown file's directory, `..` segments folded. */
    private fun relativeTo(
        docDir: String,
        path: String,
    ): String =
        "$docDir/$path"
            .split('/')
            .fold(emptyList<String>()) { acc, segment ->
                when (segment) {
                    ".." -> acc.dropLast(1)
                    ".", "" -> acc
                    else -> acc + segment
                }
            }.joinToString("/")

    private fun lineCount(file: String): Int =
        lineCounts.getOrPut(file) {
            if (tracked.contains(file)) TestFileHelpers.readFile(Path(file)).lines().size else 0
        }

    private fun gitTrackedFiles(): List<String> {
        val result = PlatformProcess.execute(listOf("git", "ls-files"))
        check(result.isSuccess) { "git ls-files failed: ${(result.error + result.output).lines().take(3)}" }
        return result.output.lines().filter { it.isNotEmpty() }
    }

    private fun refPattern(roots: Set<String>): Regex {
        val rootAlternatives = roots.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        val bareFile = """[A-Za-z][\w-]*\.(?:kt|kts|md|sh|yml|yaml|txt|api|json|toml|c|h|rs|js|gradle)"""
        return Regex("""(?<![\w./@$*}…-])((?:$rootAlternatives)/[\w./*{}<>-]*|/?$bareFile)(?::(\d+)(?:-(\d+))?)?""")
    }

    private companion object {
        const val MIN_REFS = 100
        val PATTERN_CHARS = listOf('*', '{', '<')

        /** Paths in the user's repository that the docs name as examples (scope globs, CODEOWNERS locations). */
        val USER_REPO_PATHS =
            setOf(
                "core/cpp/",
                "docs/CODEOWNERS",
                ".gitlab/CODEOWNERS",
                ".gitlab/duo/mr-review-instructions.yaml",
                ".github/CODEOWNERS",
                ".github/copilot-instructions.md",
                ".github/workflows/karandash.yml",
                ".github/workflows/karandash-reviewdog.yml",
            )
    }
}
