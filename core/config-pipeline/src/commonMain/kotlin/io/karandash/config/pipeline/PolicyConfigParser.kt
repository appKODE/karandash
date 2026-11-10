package io.karandash.config.pipeline

import io.karandash.api.exception.ConfigParseException
import io.karandash.config.PolicyConfig
import io.karandash.config.keys.PolicyKeys
import io.karandash.models.support.KarandashJson
import io.karandash.normalize.ConfigKeyNormalizer
import io.karandash.normalize.deepNormalizeKeys
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses the top-level `policies:` array from resolved config into [PolicyConfig] entries.
 *
 * Returns an empty list if the `policies:` key is absent.
 * Throws [ConfigParseException] for malformed input (non-array, missing `when:`, etc.).
 */
class PolicyConfigParser {
    /**
     * Parses policies from the top-level config object.
     *
     * @param configRoot The resolved root config as a [JsonObject].
     * @param envDefaults Env var defaults from the merged `env:` block. When provided,
     *   `${VAR}` placeholders in policy conditions and override values are interpolated
     *   before parsing so that expressions like `has_ticket(${KARANDASH_JIRA_KEY})` resolve.
     * @return Parsed policy configs, empty if `policies:` key is absent.
     * @throws ConfigParseException if the policies block is malformed.
     */
    fun parse(
        configRoot: JsonObject,
        envDefaults: Map<String, String> = emptyMap(),
    ): List<PolicyConfig> {
        val policiesElement = configRoot[PolicyKeys.POLICIES] ?: return emptyList()

        val policiesArray = policiesElement as? JsonArray
            ?: throw ConfigParseException(
                "'${PolicyKeys.POLICIES}' must be an array, got ${policiesElement::class.simpleName}",
                hint = "Write '${PolicyKeys.POLICIES}' as a list of entries, each with a '${PolicyKeys.WHEN}' condition.",
            )

        // Interpolate ${ENV_VAR} placeholders in policy conditions and override values
        val interpolatedArray = if (envDefaults.isNotEmpty()) {
            ConfigMapParser.deepInterpolateValues(policiesArray, envDefaults) as? JsonArray
                ?: policiesArray
        } else {
            policiesArray
        }

        return interpolatedArray.mapIndexed { index, element ->
            val policyObj = element as? JsonObject
                ?: throw ConfigParseException(
                    "policies[$index]: each policy entry must be an object, got ${element::class.simpleName}",
                    hint = "Write policies[$index] as an object such as { ${PolicyKeys.WHEN}: ..., ${PolicyKeys.MODULES}: [...] }.",
                )
            parseSinglePolicy(policyObj, index)
        }
    }

    private fun parseSinglePolicy(
        policyObj: JsonObject,
        index: Int
    ): PolicyConfig {
        val conditionElement = policyObj[PolicyKeys.WHEN]
            ?: throw ConfigParseException(
                "policies[$index]: '${PolicyKeys.WHEN}' is required",
                hint = "Add a '${PolicyKeys.WHEN}' condition to policies[$index].",
            )

        val conditionPrimitive = conditionElement as? JsonPrimitive
        val condition = conditionPrimitive?.takeIf { it.isString || it.booleanOrNull != null }?.contentOrNull
            ?: throw ConfigParseException(
                "policies[$index]: '${PolicyKeys.WHEN}' must be a string, got ${conditionElement::class.simpleName}",
                hint = whenHint(index),
            )

        if (condition.isBlank()) {
            throw ConfigParseException("policies[$index]: '${PolicyKeys.WHEN}' must not be empty", hint = whenHint(index))
        }

        val overrideElement = policyObj[PolicyKeys.OVERRIDE]
        if (overrideElement != null && overrideElement !is JsonObject) {
            throw ConfigParseException(
                "policies[$index]: '${PolicyKeys.OVERRIDE}' must be an object, got ${overrideElement::class.simpleName}",
                hint = "Write policies[$index].${PolicyKeys.OVERRIDE} as a map of module config.",
            )
        }

        val modulesElement = policyObj[PolicyKeys.MODULES]
        if (modulesElement != null && modulesElement !is JsonArray) {
            throw ConfigParseException(
                "policies[$index]: '${PolicyKeys.MODULES}' must be an array, got ${modulesElement::class.simpleName}",
                hint = "Write policies[$index].${PolicyKeys.MODULES} as a list of module ids.",
            )
        }

        // Normalize modules list values and override block keys from kebab-case to camelCase so
        // they match the module IDs in ruleConfigs (which are stored as camelCase after parsing).
        val normalizedPolicyObj = normalizePolicyKeys(policyObj)
        return KarandashJson.Default.decodeFromJsonElement(PolicyConfig.serializer(), normalizedPolicyObj)
    }

    private fun whenHint(index: Int): String = "Write policies[$index].${PolicyKeys.WHEN} as a quoted condition string."

    /**
     * Normalizes the `modules:` array values and every key of the `override:` block, at any depth, to camelCase so
     * they match the module ids and schema keys in `ruleConfigs`. Profile policies reach here un-normalized, so this
     * is the one place both config and profile policies agree on notation; user-defined map keys
     * ([ConfigMapParser.CONFIG_PRESERVE_KEYS]) keep their spelling as in the main config.
     */
    private fun normalizePolicyKeys(policyObj: JsonObject): JsonObject {
        val modules = (policyObj[PolicyKeys.MODULES] as? JsonArray)?.let { array ->
            JsonArray(array.map { JsonPrimitive(ConfigKeyNormalizer.kebabToCamelCase(it.jsonPrimitive.content)) })
        }
        val override = (policyObj[PolicyKeys.OVERRIDE] as? JsonObject)?.let {
            deepNormalizeKeys(it, ConfigMapParser.CONFIG_PRESERVE_KEYS)
        }
        return JsonObject(
            policyObj +
                listOfNotNull(modules?.let { PolicyKeys.MODULES to it }, override?.let { PolicyKeys.OVERRIDE to it }),
        )
    }
}
