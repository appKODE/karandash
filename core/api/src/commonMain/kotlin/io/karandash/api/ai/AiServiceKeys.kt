package io.karandash.api.ai

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.run.ServiceKey
import io.karandash.api.run.serviceKey
import kotlinx.serialization.json.JsonElement

/** Run-scoped service keys of the AI subsystem. */
@ExperimentalKarandashApi
public object AiServiceKeys {
    public val PROVIDER_SOURCE: ServiceKey<AiProviderSource> = serviceKey("ai.providerSource")
    public val SERVICE: ServiceKey<AiService> = serviceKey("ai.service")

    /** The computed AI rule config (`ai:` block after extension processing), published once parsed. */
    public val AI_CONFIG: ServiceKey<JsonElement> = serviceKey("ai.config")
}
