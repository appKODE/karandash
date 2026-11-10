package io.karandash.cli.commands.cache

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context

/**
 * Top-level `cache` command grouping local and remote cache operations.
 *
 * Subcommands:
 * - `gc`           Run garbage collection (remove expired and orphaned entries).
 * - `stats`        Show local CAS cache statistics.
 * - `clear`        Delete all local cache data.
 * - `push`         Push local CAS blobs to the configured remote backend.
 * - `remote-stats` Print remote backend configuration and status.
 *
 * Documented aliases: `info` runs `stats`, `clear-expired` runs `gc`.
 *
 * Usage:
 * ```
 * karandash cache gc
 * karandash cache stats
 * karandash cache clear
 * karandash cache push --backend-type http --backend-url https://cache.example.com
 * karandash cache remote-stats
 * ```
 */
class CacheCommand : CliktCommand(name = "cache") {
    override fun help(context: Context) = "Manage the local and remote CAS cache (aliases: info = stats, clear-expired = gc)"

    override fun aliases(): Map<String, List<String>> = mapOf("info" to listOf("stats"), "clear-expired" to listOf("gc"))

    override fun run() = Unit
}
