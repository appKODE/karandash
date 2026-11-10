package io.karandash.validators

import io.karandash.api.RuleConfig
import kotlinx.serialization.Serializable

@Serializable
data class LabelsConfig(
    val requiredLabels: List<String> = emptyList(),
    val forbiddenLabels: List<String> = emptyList(),
    val conditionalLabels: List<ConditionalLabel> = emptyList()
) : RuleConfig

@Serializable
data class ConditionalLabel(
    val condition: LabelCondition,
    val requiredLabels: List<String> = emptyList(),
    val forbiddenLabels: List<String> = emptyList()
)

@Serializable
data class LabelCondition(
    val hasLabel: String? = null,
    val hasAnyLabel: List<String>? = null,
    val hasAllLabels: List<String>? = null
)
