package io.karandash.cli.commands.report

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.CliCommandBase
import io.karandash.cli.errors.ExitCodes
import io.karandash.models.support.KarandashJson
import io.karandash.platform.readText
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Prints a summary of a validation result JSON file.
 *
 * Reads a JSON file produced by a previous `karandash validate --reporter json` run
 * and prints finding counts by severity and triggered rules.
 *
 * Usage:
 * ```
 * karandash report summary --from results.json
 * ```
 */
class ReportSummaryCommand(
    bootstrap: PluginBootstrap = PluginBootstrap(),
) : CliCommandBase("summary", bootstrap) {
    override fun help(context: Context) = "Print a summary of a validation result JSON file"

    private val from by option("--from", help = "Path to validation result JSON file").required()

    override suspend fun execute(): Int {
        val path = Path(from)
        if (!SystemFileSystem.exists(path)) {
            echo("File not found: $from", err = true)
            return ExitCodes.CONFIG_ERROR
        }

        val content = path.readText()
        val root = parseResultJson(content)
        if (root == null) {
            echo("Failed to parse validation result JSON", err = true)
            return ExitCodes.CONFIG_ERROR
        }

        val findings = root["findings"] as? JsonArray
        if (findings == null) {
            echo("No 'findings' array in result file", err = true)
            return ExitCodes.CONFIG_ERROR
        }
        printSummary(root, findings)
        return ExitCodes.SUCCESS
    }

    private fun parseResultJson(content: String): JsonObject? =
        try {
            KarandashJson.Default.parseToJsonElement(content).jsonObject
        } catch (_: kotlinx.serialization.SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun printSummary(
        root: JsonObject,
        findings: JsonArray,
    ) {
        val severityCounts = mutableMapOf<String, Int>()
        val ruleCounts = mutableMapOf<String, Int>()

        for (finding in findings) {
            val obj = finding.jsonObject
            val severity = obj["severity"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            val ruleId = obj["rule"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            severityCounts[severity] = (severityCounts[severity] ?: 0) + 1
            ruleCounts[ruleId] = (ruleCounts[ruleId] ?: 0) + 1
        }

        val total = findings.size
        val passed = root["passed"]?.jsonPrimitive?.contentOrNull

        echo("Validation Summary")
        echo("==================")
        echo("Total findings: $total")
        if (passed != null) {
            echo("Result: ${if (passed == "true") "PASSED" else "FAILED"}")
        }
        echo("")

        if (severityCounts.isNotEmpty()) {
            echo("By severity:")
            for ((severity, count) in severityCounts.entries.sortedByDescending { it.value }) {
                echo("  ${severity.padEnd(SEVERITY_COL_WIDTH)}: $count")
            }
            echo("")
        }

        if (ruleCounts.isNotEmpty()) {
            echo("By rule:")
            for ((ruleId, count) in ruleCounts.entries.sortedByDescending { it.value }) {
                echo("  ${ruleId.padEnd(RULE_COL_WIDTH)}: $count")
            }
        }
    }

    companion object {
        private const val SEVERITY_COL_WIDTH = 12
        private const val RULE_COL_WIDTH = 30
    }
}
