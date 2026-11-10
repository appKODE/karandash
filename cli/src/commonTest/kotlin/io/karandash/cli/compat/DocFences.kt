package io.karandash.cli.compat

import io.karandash.cli.TestFileHelpers
import io.karandash.normalize.ConfigKeyNormalizer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Markdown files under `docs/` (plus `README.md`) and the fenced code blocks in them, for the docs tests. */
internal object DocFences {
    class Block(
        val source: String,
        val lang: String,
        val text: String,
        /** Preceded by [SKIP_MARKER]: an intentional fragment or negative example the docs tests leave alone. */
        val skipped: Boolean,
    )

    /** Put on the line before a fence to exempt it from the docs tests; keep these few and say why next to it. */
    const val SKIP_MARKER = "<!-- docs-test: skip -->"

    fun markdownFiles(): List<Path> = markdownFiles(Path("docs")) + Path("README.md")

    fun blocks(files: List<Path> = markdownFiles()): List<Block> =
        files.flatMap { file ->
            val content = TestFileHelpers.readFile(file)
            FENCE
                .findAll(content)
                .map { match ->
                    val indent = match.groupValues[1]
                    val before = content.substring(0, match.range.first)
                    val line = before.count { it == '\n' } + 1
                    val text = match.groupValues[3].lines().joinToString("\n") { it.removePrefix(indent) }
                    val skipped = before
                        .trimEnd()
                        .lineSequence()
                        .lastOrNull()
                        ?.trim() == SKIP_MARKER
                    Block("$file:$line", match.groupValues[2], text, skipped)
                }.toList()
        }

    /** [content] with every fenced block blanked out (line count kept), for scanning prose and inline code only. */
    fun prose(content: String): String = ANY_FENCE.replace(content) { m -> "\n".repeat(m.value.count { it == '\n' }) }

    /** A KTS config example starts with `name {` and carries no Kotlin code (plugin sources, SDK snippets, Gradle scripts). */
    fun isKtsConfig(text: String): Boolean {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
        val first = lines.firstOrNull() ?: return false
        return KTS_BLOCK_START.matches(first) && lines.none { CODE_MARKER.containsMatchIn(it) || GRADLE_MARKER.containsMatchIn(it) }
    }

    /** Top-level (column 0) keys of a YAML [text], camelCased the way the config pipeline normalizes them. */
    fun yamlTopKeys(text: String): Set<String> =
        YAML_TOP_KEY.findAll(text).map { ConfigKeyNormalizer.kebabToCamelCase(it.groupValues[1]) }.toSet()

    private fun markdownFiles(dir: Path): List<Path> =
        SystemFileSystem.list(dir).sortedBy { it.name }.flatMap { path ->
            when {
                SystemFileSystem.metadataOrNull(path)?.isDirectory == true -> markdownFiles(path)
                path.name.endsWith(".md") -> listOf(path)
                else -> emptyList()
            }
        }

    private val YAML_TOP_KEY = Regex("""^([A-Za-z$][\w$-]*)\s*:""", RegexOption.MULTILINE)
    private val KTS_BLOCK_START = Regex("""[A-Za-z_]\w*\s*(\(.*\))?\s*\{.*""")
    private val CODE_MARKER = Regex("""^(class|fun|import|val|var|override|package|object|interface|data|private|internal|@)\b|->""")

    /** Gradle build scripts in the plugin and build docs are Kotlin too, but not karandash configs. */
    private val GRADLE_MARKER = Regex("""^(kotlin|pluginManagement|publishing|repositories|dependencies|tasks)\b|\b(id|kotlin)\(""")
    private val ANY_FENCE =
        Regex("""^([ \t]*)```[\w-]*[ \t]*\n.*?^\1```[ \t]*$""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
    private val FENCE = Regex("""^([ \t]*)```(\w+)[ \t]*\n(.*?)^\1```[ \t]*$""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
}
