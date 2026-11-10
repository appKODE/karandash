package io.karandash.sdk.typed

import io.karandash.models.finding.Finding
import io.karandash.sdk.config.deserializeConfigWarning
import io.karandash.sdk.module.ReporterModule
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A [ReporterModule] that uses typed configuration.
 *
 * The [report] bridge method automatically converts the [JsonElement] to [C]
 * via [configSpec] (if present) or [configSerializer].
 * Implementors override [reportTyped] instead of [report].
 *
 * @param C The typed config class for this module.
 */
public interface TypedReporterModule<C : Any> :
    ReporterModule,
    TypedModule<C> {
    /**
     * Report findings using a typed configuration object.
     *
     * @param findings the list of validation findings
     * @param passed whether validation passed overall
     * @param config typed configuration deserialized from the reporter block
     * @return optional output string (e.g. file path written)
     */
    public suspend fun reportTyped(
        findings: List<Finding>,
        passed: Boolean,
        config: C
    ): String?

    override suspend fun report(
        findings: List<Finding>,
        passed: Boolean,
        config: JsonElement
    ): String? {
        val typedConfig = deserializeConfigWarning(configSerializer, config, "reporter '$id'")
        return reportTyped(findings, passed, typedConfig)
    }

    /**
     * Extract CLI-injected overrides from [cliParams] that apply to this reporter's config.
     *
     * Override in reporter modules that accept CLI-driven values (e.g. `--quiet`, `--version`).
     *
     * @param cliParams CLI parameter [JsonElement] provided by the pipeline.
     * @return A [JsonElement] of keys to merge into the raw config (empty [JsonObject] by default).
     */
    public fun fromCliParams(cliParams: JsonElement): JsonElement = JsonObject(emptyMap())

    override suspend fun runtimeReporterConfig(
        reportersConfig: JsonElement,
        cliParams: JsonElement,
    ): JsonElement = fromCliParams(cliParams)
}
