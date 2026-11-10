package io.karandash.engine

import io.karandash.conditions.PolicyEngine
import io.karandash.config.PolicyConfig

/**
 * Policy inputs of a [ValidationEngine] run, handed on to [PolicyApplicator].
 *
 * Not @Serializable: carries the live [PolicyEngine] and lookup lambdas.
 *
 * @param engine Engine for evaluating top-level `policies:` blocks; null when there are none.
 * @param policies Parsed policy configs from the top-level `policies:` block.
 * @param envLookup Environment lookup for policy conditions.
 * @param ciPlatform Detected CI platform, if any.
 * @param isCI Whether the run is in CI.
 * @param patternExpander Expands named patterns inside policy conditions.
 */
data class PolicyOptions(
    val engine: PolicyEngine? = null,
    val policies: List<PolicyConfig> = emptyList(),
    val envLookup: (String) -> String? = { null },
    val ciPlatform: String? = null,
    val isCI: Boolean = false,
    val patternExpander: ((String) -> String)? = null,
)
