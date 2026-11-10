package com.example.karandash

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.module.ReporterModule
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Standalone example Karandash reporter plugin.
 *
 * Demonstrates implementing [ReporterModule] to produce a custom plain-text
 * summary of validation findings. This reporter is registered via ServiceLoader
 * and outputs a compact one-line-per-finding format.
 *
 * Build: ./gradlew build
 * Install: cp build/libs/standalone-jvm-plugin.jar ~/.karandash/plugins/
 */
class ExampleReporterPlugin : ReporterModule {
    override val id: String = "example-jvm-reporter"
    override val name: String = "Example JVM Reporter Plugin"
    override val version: String = "1.0.0"
    override val type: ModuleType get() = ModuleType.REPORTER
    override val formatId: String = "example-text"

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun report(
        findings: List<Finding>,
        passed: Boolean,
        config: JsonElement
    ): String? {
        val configObj = config as? JsonObject ?: JsonObject(emptyMap())
        val prefix = (configObj["prefix"] as? JsonPrimitive)?.contentOrNull ?: "[EXAMPLE]"
        val sb = StringBuilder()
        sb.appendLine("$prefix Validation ${if (passed) "PASSED" else "FAILED"}")
        if (findings.isEmpty()) {
            sb.appendLine("$prefix No findings.")
        } else {
            findings.forEach { finding ->
                val level = when (finding.severity) {
                    Severity.ERROR -> "ERROR"
                    Severity.WARNING -> "WARN"
                    Severity.INFO -> "INFO"
                }
                sb.appendLine("$prefix [$level] ${finding.rule}: ${finding.message}")
            }
        }
        return sb.toString()
    }
}
