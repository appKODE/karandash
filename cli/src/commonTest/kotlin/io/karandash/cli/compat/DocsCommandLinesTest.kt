package io.karandash.cli.compat

import com.github.ajalt.clikt.core.BaseCliktCommand
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.CliLogLevel
import io.karandash.cli.config.renderers.registerBuiltInRenderers
import io.karandash.cli.karandashCommandTree
import io.karandash.cli.plugin.buildPluginCliCommands
import io.karandash.plugins.ai.core.AiEnvVars
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Every `karandash ...` line in a shell fence of the docs names subcommands and options that exist in the real
 * command tree (built-in commands plus built-in plugin commands). Positional values are not checked, nor are the
 * options of plugin leaf commands, which parse their own flags.
 */
class DocsCommandLinesTest {
    private class CommandLine(
        val source: String,
        val text: String,
        val tokens: List<String>,
    )

    @Test
    fun `every karandash command line in the docs parses against the command tree`() =
        runTest(timeout = 5.minutes) {
            // An AI provider host registers the env-gated `ai` command group; nothing is contacted while parsing.
            val env = mapOf(AiEnvVars.OLLAMA_HOST to "http://127.0.0.1:1")
            val bootstrap = PluginBootstrap(envReader = env::get, onFormatRegistryCreated = ::registerBuiltInRenderers)
            bootstrap.loadPluginsFromDirs(emptyList())
            try {
                val tree = commandTree(bootstrap)
                val lines = commandLines()
                assertTrue(lines.size >= MIN_LINES, "expected at least $MIN_LINES command lines, got ${lines.size}")
                val problems = lines.flatMap { line -> problems(tree, line.tokens).map { line to it } }
                val unexpected = problems.map { (line, p) -> "${line.source}: '$p' in `${line.text}`" }
                assertEquals(emptyList(), unexpected, unexpected.joinToString("\n"))
            } finally {
                bootstrap.shutdown()
            }
        }

    private suspend fun commandTree(bootstrap: PluginBootstrap): BaseCliktCommand<*> {
        val builtIn = karandashCommandTree(bootstrap, CliLogLevel.QUIET, null, emptyList())
        val reserved = builtIn.registeredSubcommands().map { it.commandName }.toSet()
        val pluginCommands = buildPluginCliCommands(bootstrap.registry(), bootstrap, reserved, quiet = true)
        return karandashCommandTree(bootstrap, CliLogLevel.QUIET, null, pluginCommands)
    }

    /**
     * Walks [tokens] (after `karandash`) down the tree. A problem is the command path plus the unknown subcommand or
     * option, e.g. `plugin info` or `validate --once`.
     */
    private fun problems(
        root: BaseCliktCommand<*>,
        tokens: List<String>,
    ): List<String> {
        var command = root
        val words = tokens.toMutableList()
        val path = mutableListOf<String>()
        val problems = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            val token = words[i++]
            val takesSubcommand = command.registeredSubcommands().isNotEmpty() && command.registeredArguments().isEmpty()
            when {
                token == END_OF_OPTIONS || (isPlaceholder(token) && takesSubcommand) -> {
                    break
                }

                token.length > 1 && token.startsWith("-") && !isPlaceholder(token) -> {
                    val name = token.substringBefore('=')
                    val option = command.registeredOptions().firstOrNull { name in it.names || name in it.secondaryNames }
                    when {
                        name in HELP_FLAGS -> Unit
                        option == null -> if (!command.treatUnknownOptionsAsArgs) problems += (path + name).joinToString(" ")
                        '=' !in token && option.nvalues.first > 0 -> i++
                    }
                }

                token in command.aliases() -> {
                    words.addAll(i, checkNotNull(command.aliases()[token]))
                }

                else -> {
                    val sub = command.registeredSubcommands().firstOrNull { it.commandName == token }
                    if (sub != null) {
                        command = sub
                        path += token
                    } else if (takesSubcommand) {
                        problems += (path + token).joinToString(" ")
                        break
                    }
                }
            }
        }
        return problems
    }

    private fun isPlaceholder(token: String): Boolean = token.first() in PLACEHOLDER_START || token.contains("<") || token == "..."

    private fun commandLines(): List<CommandLine> =
        DocFences.blocks().filter { it.lang in SHELL_LANGS && !it.skipped }.flatMap { block ->
            block.text
                .replace("\\\n", " ")
                .lines()
                .map { it.trim().removePrefix("$ ") }
                .filter { it.startsWith(KARANDASH) }
                .map { text -> CommandLine(block.source, text, shellWords(text).drop(1)) }
        }

    /** Words of a shell line up to the first pipe, redirect, list operator or comment; quotes removed. */
    private fun shellWords(line: String): List<String> =
        SHELL_WORD
            .findAll(line)
            .map { it.value }
            .takeWhile { word -> STOP_WORD.none { word.startsWith(it) } }
            .map { it.removeSuffix(";").trim('"', '\'') }
            .filter { it.isNotEmpty() }
            .toList()

    private companion object {
        const val KARANDASH = "karandash "
        const val END_OF_OPTIONS = "--"
        const val MIN_LINES = 300
        val SHELL_LANGS = setOf("bash", "sh", "shell", "console", "zsh")
        val HELP_FLAGS = setOf("-h", "--help")
        val PLACEHOLDER_START = setOf('<', '[', '{', '$')
        val STOP_WORD = listOf("|", "&", ";", ">", "2>", "#", "<<")
        val SHELL_WORD = Regex(""""[^"]*"|'[^']*'|\S+""")
    }
}
