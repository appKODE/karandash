package io.karandash.api

import kotlinx.serialization.Serializable

@Serializable
public enum class ModuleType(
    /** CLI filter label for `karandash plugin search --type <label>`. */
    public val cliLabel: String
) {
    RULE("rule"),
    REPORTER("reporter"),
    CLI_COMMAND("cli-command"),
    CONFIG_FORMAT("config-format"),
    PROFILE("profile"),
    AI_PROVIDER("ai-provider"),
    AI_INTERCEPTOR("ai-interceptor"),
    AI_ANALYZER("ai-analyzer"),
    AI_PROMPT_PROCESSOR("ai-prompt-processor"),
    AI_RESPONSE_TRANSFORMER("ai-response-transformer"),
    AI_CONTEXT_ENRICHER("ai-context-enricher"),
    AI_REDACTOR("ai-redactor"),
    AI_TOKEN_ALLOCATOR("ai-token-allocator"),
    SCRIPT("script"),
    PIPELINE_EXTENSION("pipeline-extension"),
    PLATFORM_SERVICE("platform-service"),
    ENRICHMENT_PROVIDER("enrichment-provider"),
    FORMAT("format"),
    OUTPUT("output"),
    AI_REPORT("ai-report"),
    AI_METRIC("ai-metric"),
    AI_PIPELINE_STAGE("ai-pipeline-stage"),
    TRANSLATION("translation"),
    ;

    public companion object {
        /**
         * Parses a raw type string into a [ModuleType].
         *
         * Accepts enum names (case-insensitive, hyphens normalised to underscores)
         * and [cliLabel] values (case-insensitive).
         *
         * @param typeStr The raw type string to parse.
         * @return The matching [ModuleType].
         * @throws IllegalArgumentException if the string does not match any known type.
         */
        public fun fromString(typeStr: String): ModuleType {
            val normalized = typeStr.trim().uppercase().replace("-", "_")
            return entries.firstOrNull { it.name == normalized }
                ?: entries.firstOrNull { it.cliLabel.equals(typeStr.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Unsupported module type '$typeStr'. Expected one of: ${entries.joinToString { it.name }}"
                )
        }
    }
}
