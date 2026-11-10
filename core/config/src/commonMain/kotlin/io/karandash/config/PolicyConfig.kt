package io.karandash.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A single entry in the top-level `policies:` config block.
 *
 * When [condition] evaluates to true against the current MR:
 * - [override] is deep-merged into the configs of affected modules
 * - [enabled] overrides the enabled state of affected modules
 * - [severity] overrides the severity for all findings from affected modules
 *
 * [modules] scopes the policy to specific module IDs. Null means all modules.
 *
 * Pipeline stage controls ([autoApprove], [autoFix], [scripts], [dangerfile])
 * gate whether specific pipeline stages execute when the policy matches.
 */
@Serializable
data class PolicyConfig(
    @SerialName("when") val condition: String,
    val name: String? = null,
    val modules: List<String>? = null,
    val enabled: Boolean? = null,
    val severity: String? = null,
    val override: JsonObject? = null,
    @SerialName("autoApprove") val autoApprove: Boolean? = null,
    @SerialName("autoFix") val autoFix: Boolean? = null,
    val scripts: Boolean? = null,
    val dangerfile: Boolean? = null,
)
