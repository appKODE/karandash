package com.example.karandash

import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.sdk.module.AbstractRuleModule
import io.karandash.sdk.regex.SafeRegex
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Standalone example Karandash rule plugin.
 *
 * This plugin demonstrates:
 * - Extending AbstractRuleModule for reduced boilerplate
 * - Validating MR titles against a pattern
 * - Configuration handling with defaults
 * - Compiling user-supplied regex with [SafeRegex]
 * - Proper ServiceLoader registration
 *
 * Build: ./gradlew build
 * Install: cp build/libs/standalone-jvm-plugin.jar ~/.karandash/plugins/
 */
class ExampleRulePlugin : AbstractRuleModule() {
    override val id: String = "example-jvm-plugin"
    override val name: String = "Example JVM Rule Plugin"
    override val version: String = "1.0.0"

    override suspend fun contributedRules(): Set<String> {
        return setOf(RULE_ID)
    }

    override suspend fun validate(
        ruleId: String,
        mr: MergeRequest,
        configElement: JsonElement
    ): List<Finding> {
        return when (ruleId) {
            RULE_ID -> validateTitlePattern(mr, configElement)
            else -> emptyList()
        }
    }

    /**
     * Validates that MR titles match a configured pattern.
     *
     * Configuration:
     * - pattern: String - Regex pattern to match (default: "^\\[\\w+\\].*")
     * - message: String - Custom error message
     */
    private fun validateTitlePattern(mr: MergeRequest, configElement: JsonElement): List<Finding> {
        val config = configElement as? JsonObject ?: JsonObject(emptyMap())
        val pattern = (config["pattern"] as? JsonPrimitive)?.contentOrNull ?: "^\\[\\w+\\].*"
        val customMessage = (config["message"] as? JsonPrimitive)?.contentOrNull

        // User-supplied pattern: compile through the SDK's ReDoS guards, never Regex(pattern).
        val findings = mutableListOf<Finding>()
        val regex = SafeRegex.tryCompile(pattern, RULE_ID, findings) ?: return findings

        if (!mr.title.matches(regex)) {
            val message = customMessage
                ?: "MR title '${mr.title}' does not match required pattern: $pattern"

            return listOf(
                Finding(
                    rule = RULE_ID,
                    message = message,
                    severity = Severity.ERROR,
                    location = null
                )
            )
        }

        return emptyList()
    }

    companion object {
        /** camelCase: the host normalizes kebab-case and snake_case config keys to camelCase. */
        const val RULE_ID = "exampleTitlePattern"
    }
}
