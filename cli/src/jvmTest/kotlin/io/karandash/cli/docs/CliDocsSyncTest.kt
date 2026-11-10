package io.karandash.cli.docs

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The command table and every command line in the docs match the real clikt tree
 * (`--help` from the root down), so a removed flag or a made-up command fails here instead of in a user's terminal.
 */
class CliDocsSyncTest {
    @Test
    fun `command table lists exactly the commands the root help lists`() =
        runTest(timeout = 5.minutes) {
            val tree = HelpTree.load()
            val table = DocsSupport.read("docs/reference/cli-reference.md").substringAfter("\n## Commands").substringBefore("\n## ")
            val documented = Regex("(?m)^\\| \\[?`([a-z-]+)`").findAll(table).map { it.groupValues[1] }.toSet()
            // `ai` is registered only when an AI provider env var is set, so the bare tree cannot list it.
            assertEquals(tree.subcommands(emptyList()).toSet() + "ai", documented)
        }

    @Test
    fun `explain is described as the config explainer it is`() {
        val row = DocsSupport.read("docs/reference/cli-reference.md").lines().first { it.startsWith("| [`explain`]") }
        assertTrue(!row.contains("finding") && row.contains("config"), row)
    }

    @Test
    fun `every karandash command line in the docs uses real commands and flags`() =
        runTest(timeout = 5.minutes) {
            val tree = HelpTree.load()
            val rootFlags = tree.flags(emptyList())
            val bad = markdownFiles().flatMap { (name, text) ->
                commandLines(text).mapNotNull { line -> problem(tree, rootFlags, line)?.let { "$name: $line -> $it" } }
            }
            assertTrue(bad.isEmpty(), "docs use commands or flags that do not exist:\n" + bad.joinToString("\n"))
        }

    private fun problem(
        tree: HelpTree,
        rootFlags: Set<String>,
        line: String,
    ): String? {
        val tokens = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
        // `ai` is registered only when an AI provider env var is set, so the bare tree cannot describe it.
        if (tokens.firstOrNull() == "ai" || tokens.firstOrNull()?.firstOrNull()?.isLetter() != true) return null
        var path = emptyList<String>()
        for (t in tokens) {
            if (t.startsWith("-")) break
            if (t in tree.subcommands(path)) path = path + t else break
        }
        if (path.isEmpty() && tokens.firstOrNull()?.startsWith("-") != true) return "unknown command '${tokens.firstOrNull()}'"
        val known = tree.flags(path) + rootFlags
        val unknown = tokens.filter { it.startsWith("--") }.mapNotNull { FLAG.find(it)?.value }.filter { it !in known }
        return if (unknown.isEmpty()) null else "unknown flag(s) $unknown for '${path.joinToString(" ")}'"
    }

    /** Text after `karandash` / `java -jar ...karandash.jar` for command lines in fenced shell blocks (continuations joined). */
    private fun commandLines(markdown: String): List<String> {
        val blocks = Regex("```(?:bash|sh|shell|console)\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(markdown)
        return blocks
            .flatMap { it.groupValues[1].replace("\\\n", " ").lines() }
            .mapNotNull { raw ->
                COMMAND
                    .find(raw.trim())
                    ?.groupValues
                    ?.get(1)
                    ?.takeIf { it.isNotBlank() }
            }.toList()
    }

    private fun markdownFiles(): List<Pair<String, String>> {
        val root = Path(DocsSupport.path(".").toString())
        return SCANNED
            .flatMap { dir ->
                val start = root.resolve(dir)
                if (start.isRegularFile()) listOf(start) else Files.walk(start).use { s -> s.filter { it.extension == "md" }.toList() }
            }.filterNot { it.name == "CHANGELOG.md" }
            .map { it.relativeTo(root).toString() to it.readText() }
    }

    private companion object {
        val SCANNED = listOf("README.md", "docs", "tools")
        val FLAG = Regex("--[a-z][a-z0-9-]*")
        val COMMAND = Regex("^(?:\\$ )?(?:karandash|java -jar \\S*karandash\\S*\\.jar)\\s+(.*)$")
    }
}
