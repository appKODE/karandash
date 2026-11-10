package io.karandash.validators

import io.karandash.api.RuleConfig
import kotlinx.serialization.Serializable

@Serializable
data class SectionRule(
    val name: String,
    val pattern: String,
    val required: Boolean = false,
)

@Serializable
data class DescriptionFormatConfig(
    val mrSections: List<SectionRule> = emptyList(),
) : RuleConfig
