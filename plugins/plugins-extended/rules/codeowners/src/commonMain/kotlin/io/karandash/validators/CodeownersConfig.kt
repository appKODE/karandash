package io.karandash.validators

import io.karandash.api.RuleConfig
import kotlinx.serialization.Serializable

@Serializable
data class CodeownersConfig(
    val path: String = DEFAULT_PATH,
    val requireOwnerApproval: Boolean = true
) : RuleConfig {
    companion object {
        const val DEFAULT_PATH = "CODEOWNERS"
    }
}
