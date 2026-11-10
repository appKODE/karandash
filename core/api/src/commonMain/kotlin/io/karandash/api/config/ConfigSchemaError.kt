package io.karandash.api.config

import kotlinx.serialization.Serializable

/**
 * Severity level for config schema errors.
 *
 * - [FATAL]: Pipeline stops; the config cannot be used.
 * - [WARNING]: Issue logged; validation continues with the raw/default config.
 * - [INFO]: Informational; no action required.
 */
@Serializable
public enum class ErrorSeverity { FATAL, WARNING, INFO }

/**
 * Represents an error or warning encountered while coercing a raw config map
 * to a module's typed config via [KSerializer]-based deserialization.
 *
 * @property moduleId The ID of the module whose config had an issue.
 * @property field The config field that caused the issue (dot-separated for nested fields).
 * @property message Human-readable description of the issue.
 * @property severity How serious the issue is.
 */
@Serializable
public data class ConfigSchemaError(
    val moduleId: String,
    val field: String,
    val message: String,
    val severity: ErrorSeverity,
)
