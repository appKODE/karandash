package io.karandash.cli.docs

import io.karandash.cli.compat.ConsoleCapture
import io.karandash.cli.runCli

/** The real clikt tree, read the way a user sees it: `--help` from the root down. */
internal class HelpTree private constructor(
    private val helpByPath: Map<List<String>, String>,
) {
    /** Command paths (`["plugin", "install"]`), root excluded. */
    val paths: Set<List<String>> get() = helpByPath.keys - setOf(emptyList())

    fun subcommands(path: List<String>): List<String> = subcommands(helpByPath.getValue(path))

    /** Long flags declared in the Options section of [path]'s help. */
    fun flags(path: List<String>): Set<String> =
        helpByPath
            .getValue(path)
            .substringAfter("Options:", "")
            .substringBefore("Commands:")
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("-") }
            .flatMap { FLAG.findAll(it.substringBefore("  ")).map { m -> m.value } }
            .toSet()

    companion object {
        private val FLAG = Regex("--[a-z][a-z0-9-]*")

        suspend fun load(): HelpTree {
            val helpByPath = linkedMapOf<List<String>, String>()
            val pending = ArrayDeque(listOf(emptyList<String>()))
            while (pending.isNotEmpty()) {
                val path = pending.removeFirst()
                val (_, out, _) = ConsoleCapture.capture { runCli((path + "--help").toTypedArray()) }
                helpByPath[path] = out
                subcommands(out).forEach { pending.addLast(path + it) }
            }
            return HelpTree(helpByPath)
        }

        /** Names listed under `Commands:` (two-space indent; wrapped descriptions are indented further). */
        private fun subcommands(help: String): List<String> =
            help
                .substringAfter("Commands:", "")
                .lineSequence()
                .drop(1)
                .takeWhile { it.isNotBlank() }
                .filter { it.startsWith("  ") && !it.startsWith("   ") }
                .map { it.trim().substringBefore(' ') }
                .toList()
    }
}
