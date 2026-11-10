package io.karandash.plugins.ai.core.architecture

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Turns the top-level `policies:` array into [PolicyDefinition]s for [PolicyVerifier]. */
object PolicyDefinitions {
    private const val ALL_MODULES = "*"
    private val EFFECTS = listOf("enabled", "severity", "autoApprove", "autoFix", "scripts", "dangerfile")

    /**
     * One definition per (policy, effect) set; the effect is folded into scope so only same-effect pairs overlap.
     * Policies without `modules` cover every module any policy names plus `*`. Non-object entries are skipped.
     */
    fun from(policies: JsonArray): List<PolicyDefinition> {
        val objects = policies.filterIsInstance<JsonObject>()
        val allModules = objects.flatMap { modules(it).orEmpty() }.distinct() + ALL_MODULES
        return objects.flatMapIndexed { index, policy ->
            val name = string(policy, "name") ?: "policy #${index + 1}"
            val modules = modules(policy) ?: allModules
            EFFECTS.mapNotNull { effect ->
                val value = string(policy, effect) ?: return@mapNotNull null
                PolicyDefinition(
                    id = "$index:$effect",
                    name = "$name ($effect)",
                    scope = modules.map { "$effect:$it" },
                    condition = string(policy, "when").orEmpty(),
                    action = "$effect=$value",
                )
            }
        }
    }

    private fun string(
        policy: JsonObject,
        key: String,
    ): String? = (policy[key] as? JsonPrimitive)?.contentOrNull

    private fun modules(policy: JsonObject): List<String>? =
        (policy["modules"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}
