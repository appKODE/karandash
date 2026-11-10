package io.karandash.sdk.config

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

/**
 * DSL builder for constructing type-safe profile rule configurations.
 *
 * Converts typed `@Serializable` config data classes to the `Map<String, JsonElement>`
 * format expected by [ProfileModule.ruleConfigs], with compile-time safety for field names.
 */
public class ProfileRuleConfigBuilder
    @PublishedApi
    internal constructor() {
        @PublishedApi
        internal val entries: MutableMap<String, JsonElement> = mutableMapOf()

        /**
         * Adds a rule configuration entry by serializing the typed [config] object to a [JsonElement].
         *
         * @param ruleId the camelCase rule identifier (e.g., [io.karandash.validators.TitleFormatRuleModule.RULE_ID])
         * @param config a `@Serializable` config data class instance
         */
        public inline fun <reified T : Any> rule(
            ruleId: String,
            config: T,
        ) {
            entries[ruleId] = sdkConfigMapJson.encodeToJsonElement(config)
        }

        /**
         * Adds a rule configuration entry that also declares the rule's run [severity] (`error`, `warning`, `info`).
         * The host lifts it (under [PROFILE_RULE_SEVERITY_KEY]) out of the rule config into the rule's execution metadata, so a
         * user's own `severity:` still wins.
         */
        public inline fun <reified T : Any> rule(
            ruleId: String,
            config: T,
            severity: String,
        ) {
            entries[ruleId] = withMetaSeverity(ruleId, sdkConfigMapJson.encodeToJsonElement(config), severity)
        }

        @PublishedApi
        internal fun build(): Map<String, JsonElement> = entries.toMap()
    }

/**
 * Reserved key under which a profile declares a rule's run-severity inside its rule config. The host lifts it into
 * rule metadata. It is not `severity`, which some rule configs use themselves.
 */
public const val PROFILE_RULE_SEVERITY_KEY: String = "@severity"

@PublishedApi
internal fun withMetaSeverity(
    ruleId: String,
    encoded: JsonElement,
    severity: String,
): JsonElement {
    val fields = (encoded as? JsonObject) ?: error("Rule '$ruleId' config must encode to a JSON object")
    return JsonObject(fields + (PROFILE_RULE_SEVERITY_KEY to JsonPrimitive(severity)))
}

/**
 * Builds a type-safe profile rule configuration map.
 *
 * Example:
 * ```kotlin
 * override suspend fun ruleConfigs() = profileRuleConfigs {
 *     rule(TitleFormatRuleModule.RULE_ID, TitleFormatConfig(pattern = "^feat:.*"))
 *     rule(SizeLimitsRuleModule.RULE_ID, SizeLimitsConfig(maxLines = 500))
 * }
 * ```
 *
 * @return a map of rule IDs to their serialized [JsonElement] configurations
 */
public inline fun profileRuleConfigs(block: ProfileRuleConfigBuilder.() -> Unit): Map<String, JsonElement> =
    ProfileRuleConfigBuilder().apply(block).build()
