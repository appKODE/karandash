package io.karandash.validators

import io.karandash.api.RuleConfig
import io.karandash.api.config.PreserveKeys
import kotlinx.serialization.Serializable

@Serializable
data class DependencyValidationConfig(
    val warnOnUnpinnedVersions: Boolean = true,
    @PreserveKeys
    val minVersions: Map<String, String> = emptyMap(),
    val blockedPatterns: List<String> = emptyList(),
) : RuleConfig
