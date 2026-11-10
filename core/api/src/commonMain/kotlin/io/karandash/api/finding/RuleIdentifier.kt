package io.karandash.api.finding

import kotlinx.serialization.Serializable

/**
 * Lightweight wrapper for rule identification.
 *
 * All rules - both built-in and external - are identified by plain string keys.
 * This class provides a typed handle for the key and serves as an extension point
 * for rule-related utilities (e.g., [io.karandash.api.ruleIdentifier]).
 *
 * @param key The string key used in config maps and findings.
 */
@Serializable
public data class RuleIdentifier(
    val key: String,
) {
    public companion object {
        /**
         * Create a [RuleIdentifier] from a config/finding key.
         */
        public fun from(key: String): RuleIdentifier = RuleIdentifier(key)
    }
}
