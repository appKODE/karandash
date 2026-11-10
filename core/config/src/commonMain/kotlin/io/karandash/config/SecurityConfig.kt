package io.karandash.config

import kotlinx.serialization.Serializable

/**
 * Network security settings from the top-level `security:` block.
 *
 * @property blockedHosts Extra hostnames every outbound URL check refuses, on top of the built-in metadata and
 *   link-local hosts; it only adds blocks, never lifts one. Applied per run via
 *   `SsrfUrlValidator.configureAdditionalBlockedHosts`.
 */
@Serializable
data class SecurityConfig(
    val blockedHosts: List<String> = emptyList(),
)
