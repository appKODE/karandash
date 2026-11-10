package io.karandash.plugins.ai.pipeline

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** `{ enabled: ... }` block; `key: true` is shorthand for it. */
@Serializable
internal data class EnabledBlock(
    val enabled: Boolean = true,
)

/** `ai.architecture.provenanceChain` block. */
@Serializable
internal data class ProvenanceChainOptions(
    val enabled: Boolean = true,
    /** JSON Lines file each run appends one hash-linked entry to; absent = no file. */
    val persistChain: String? = null,
)

/** `ai.architecture.policyVerification` block. */
@Serializable
internal data class PolicyVerificationOptions(
    val enabled: Boolean = true,
    /** `false` logs the issues as warnings instead of adding findings. */
    val reportIssues: Boolean = true,
)

private const val DEFAULT_DETECTION_WINDOW_DAYS = 7

/** `ai.dependencies` block. */
@Serializable
internal data class DependenciesOptions(
    val enabled: Boolean = true,
    /** `false` skips the shared-file conflict findings; delete/rename ordering findings stay. */
    val checkConflicts: Boolean = true,
    /** Days a validated MR is remembered as an open sibling. */
    val detectionWindow: Int = DEFAULT_DETECTION_WINDOW_DAYS,
)

/**
 * `ai.architecture.*` (and `ai.dependencies`, `ai.mutationTesting`) options read from the raw `ai` block. A block that is absent, `false` or `enabled: false` is off;
 * a malformed block is ignored with a warning.
 */
internal class AiArchitectureOptions(
    /** `ai.architecture.semanticDiff`: report removed public API declarations. */
    val semanticDiff: Boolean = false,
    /** `ai.architecture.provenanceChain`; null when off. */
    val provenanceChain: ProvenanceChainOptions? = null,
    /** `ai.architecture.policyVerification`; null when off. */
    val policyVerification: PolicyVerificationOptions? = null,
    /** `ai.dependencies` (a top-level `ai` key); null when off. */
    val dependencies: DependenciesOptions? = null,
    /** `ai.mutationTesting` (a top-level `ai` key): score the configured rules against synthetic bad MRs. */
    val mutationTesting: Boolean = false,
) {
    /** Reports these options turn on, as `ai.reports` would. */
    val reportIds: Set<String> get() = if (provenanceChain != null) setOf(AiReportGenerator.PROVENANCE_CHAIN_REPORT) else emptySet()

    companion object {
        /** Reads the options from the `ai` block (or a config whose `ai` key holds it). */
        fun from(
            config: JsonElement?,
            warn: (String) -> Unit,
        ): AiArchitectureOptions {
            val obj = config as? JsonObject ?: return AiArchitectureOptions()
            val ai = obj["ai"] as? JsonObject ?: obj
            val architecture = ai["architecture"] as? JsonObject ?: JsonObject(emptyMap())
            return AiArchitectureOptions(
                semanticDiff = option<EnabledBlock>(architecture, "architecture.semanticDiff", warn)?.enabled == true,
                provenanceChain = option<ProvenanceChainOptions>(architecture, "architecture.provenanceChain", warn)?.takeIf { it.enabled },
                policyVerification =
                    option<PolicyVerificationOptions>(architecture, "architecture.policyVerification", warn)?.takeIf { it.enabled },
                dependencies = option<DependenciesOptions>(ai, "dependencies", warn)?.takeIf { it.enabled },
                mutationTesting = option<EnabledBlock>(ai, "mutationTesting", warn)?.enabled == true,
            )
        }

        /** Decodes `section[last segment of path]`; [path] (relative to `ai`) names it in warnings. */
        private inline fun <reified T> option(
            section: JsonObject,
            path: String,
            noinline warn: (String) -> Unit,
        ): T? = AiMetricsOptions.decode<T>(AiMetricsOptions.block(section[path.substringAfterLast('.')]), path, warn)
    }
}
