package io.karandash.sdk.typed

import io.karandash.sdk.config.deserializeConfigWarning
import io.karandash.sdk.finding.FormattedReport
import io.karandash.sdk.module.OutputModule
import kotlinx.serialization.json.JsonElement

/**
 * An [OutputModule] that uses typed configuration.
 *
 * The [output] bridge method automatically converts the [JsonElement] to [C]
 * via [configSerializer]. Implementors override [outputTyped] instead.
 *
 * @param C The typed config class for this module.
 */
public interface TypedOutputModule<C : Any> :
    OutputModule,
    TypedModule<C> {
    /**
     * Deliver a [FormattedReport] using typed configuration.
     *
     * @param report The formatted report to deliver.
     * @param config Typed configuration deserialized from the output config block.
     */
    public suspend fun outputTyped(
        report: FormattedReport,
        config: C,
    )

    override suspend fun output(
        report: FormattedReport,
        config: JsonElement,
    ) {
        val typedConfig = deserializeConfigWarning(configSerializer, config, "output '$outputId'")
        outputTyped(report, typedConfig)
    }
}
