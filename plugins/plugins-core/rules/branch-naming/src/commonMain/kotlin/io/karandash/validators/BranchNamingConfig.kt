package io.karandash.validators

import io.karandash.api.RuleConfig
import io.karandash.patterns.CaseType
import kotlinx.serialization.Serializable

@Serializable
data class BranchNamingConfig(
    val pattern: String? = null,
    val allowedPatterns: List<String> = emptyList(),
    val requireSourceMatch: Boolean = true,
    val requireTargetMatch: Boolean = false,
    // Structured config - alternative to pattern/allowedPatterns. Ignored when pattern is set.
    val prefixOneOf: List<String>? = null,
    val case: CaseType? = null,
    val maxLength: Int? = null,
) : RuleConfig
