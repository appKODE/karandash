package io.karandash.api.platform

import kotlinx.serialization.Serializable

/**
 * Declares a single parameter accepted by a named pattern definition.
 *
 * @param name Parameter name referenced in the pattern expression.
 * @param description Human-readable description shown in IDE completions and docs.
 * @param defaultValue Optional default when the caller omits this parameter.
 */
@Serializable
public data class PatternParam(
    val name: String,
    val description: String = "",
    val defaultValue: String? = null,
)
