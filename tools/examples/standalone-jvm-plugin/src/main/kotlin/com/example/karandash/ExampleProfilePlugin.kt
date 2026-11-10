package com.example.karandash

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.module.ProfileModule
import io.karandash.models.finding.Severity
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Conventional git subject-line limit the example team enforces on MR titles. */
private const val MAX_TITLE_LENGTH = 72

/**
 * Standalone example Karandash profile plugin.
 *
 * Demonstrates implementing [ProfileModule] to bundle a predefined set of rule
 * configurations encoding a team workflow convention. This example profile enforces
 * a simple "example-team" convention with title format and draft detection rules.
 *
 * Usage in config:
 * ```yaml
 * profile: example-team
 * ```
 *
 * Build: ./gradlew build
 * Install: cp build/libs/standalone-jvm-plugin.jar ~/.karandash/plugins/
 */
class ExampleProfilePlugin : ProfileModule {
    override val id: String = "example-jvm-profile"
    override val name: String = "Example JVM Profile Plugin"
    override val version: String = "1.0.0"
    override val type: ModuleType get() = ModuleType.PROFILE

    override val profileId: String = "example-team"
    override val summary: String = "Example team workflow profile with basic title and draft conventions."
    override suspend fun tags(): Set<String> = setOf("title", "draft", "example")
    override val defaultSeverity: Severity = Severity.WARNING

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun ruleConfigs(): Map<String, JsonElement> {
        return mapOf(
            "titleFormat" to JsonObject(
                mapOf(
                    "pattern" to JsonPrimitive("^(feat|fix|chore|docs|refactor)(\\(.+\\))?: .+"),
                    "maxLength" to JsonPrimitive(MAX_TITLE_LENGTH)
                )
            ),
            "draftPrevention" to JsonObject(
                mapOf(
                    "enabled" to JsonPrimitive(true)
                )
            )
        )
    }

    override suspend fun reporterConfigs(): Map<String, JsonElement> {
        return mapOf(
            "console" to JsonObject(
                mapOf(
                    "showSummary" to JsonPrimitive(true)
                )
            )
        )
    }
}
