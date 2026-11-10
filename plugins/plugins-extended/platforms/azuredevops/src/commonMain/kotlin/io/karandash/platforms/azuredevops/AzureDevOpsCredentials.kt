package io.karandash.platforms.azuredevops

import io.karandash.models.support.KarandashJson
import io.karandash.providers.common.CredentialsSupport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

object AzureDevOpsCredentials {
    @Serializable
    data class Resolved(
        val token: String?
    )

    /**
     * Resolve Azure DevOps credentials from config and environment.
     *
     * Precedence:
     * - Token: config.token > AZURE_DEVOPS_TOKEN > SYSTEM_ACCESSTOKEN (optional)
     */
    fun resolve(
        config: JsonElement,
        env: (String) -> String?
    ): Resolved {
        val cfg = CredentialsSupport.decodeOrDefault(config, AzureDevOpsConfig.serializer()) { AzureDevOpsConfig() }

        val token = CredentialsSupport.firstNonBlank(cfg.token, env, "AZURE_DEVOPS_TOKEN", "SYSTEM_ACCESSTOKEN")

        return Resolved(token = token)
    }

    fun supportedEnvVars(): Set<String> =
        setOf(
            "AZURE_DEVOPS_TOKEN",
            "SYSTEM_ACCESSTOKEN"
        )
}
