package io.karandash.validators

import io.karandash.api.RuleConfig
import io.karandash.patterns.TicketFormat
import kotlinx.serialization.Serializable

@Serializable
data class JiraTicketConfig(
    val projectKeys: List<String> = emptyList(),
    val pattern: String? = null,
    val validateStatus: List<String>? = null,
    val validateAssignee: Boolean = false,
    val checkIn: String = CHECK_IN_BOTH,
    val jiraUrl: String? = null,
    // Structured config - alternative to `pattern`. Ignored when `pattern` is set.
    val format: TicketFormat? = null,
    val projects: List<String>? = null,
) : RuleConfig {
    companion object {
        const val CHECK_IN_TITLE = "title"
        const val CHECK_IN_DESCRIPTION = "description"
        const val CHECK_IN_BOTH = "both"
    }

    /**
     * Returns the effective project keys: `projects` (structured) takes precedence over
     * `projectKeys`, falling back to an empty list. Blank entries (an unset `${'$'}{KEY}`
     * interpolates to `""`) are dropped, so they mean "no keys" rather than a key that matches nothing.
     */
    fun effectiveProjectKeys(): List<String> = (projects ?: projectKeys).filter { it.isNotBlank() }
}
