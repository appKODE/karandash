package io.karandash.validators

import io.karandash.api.RuleConfig
import io.karandash.patterns.TicketFormat
import kotlinx.serialization.Serializable

@Serializable
data class CrossEntityConsistencyConfig(
    val taskIdPattern: String? = null,
    val commitTasksSubsetOfMr: Boolean = true,
    val changelogConsistency: Boolean = true,
    val changelogDuplicateDetection: Boolean = true,
    val changelogPrefix: String = DEFAULT_CHANGELOG_PREFIX,
    val mrTaskSource: String = DEFAULT_MR_TASK_SOURCE,
    val changelogDuplicateRequiresMatchingText: Boolean = false,
    val changelogCoverage: Boolean = false,
    val ticketFormat: TicketFormat? = null,
    val ticketProjects: List<String>? = null,
) : RuleConfig {
    init {
        require(mrTaskSource in VALID_MR_TASK_SOURCES) {
            "Invalid mrTaskSource '$mrTaskSource': must be one of $VALID_MR_TASK_SOURCES"
        }
    }

    /**
     * Returns the effective task ID regex pattern.
     *
     * Priority: explicit [taskIdPattern] > [ticketFormat]+[ticketProjects]-generated > default.
     */
    fun effectiveTaskIdPattern(): String = taskIdPattern ?: buildFromStructured() ?: DEFAULT_TASK_ID_PATTERN

    private fun buildFromStructured(): String? {
        val format = ticketFormat ?: return null
        val prefix = buildProjectPrefix()
        return when (format) {
            TicketFormat.JIRA, TicketFormat.LINEAR, TicketFormat.YOUTRACK -> "\\b$prefix-\\d+"
            TicketFormat.GITHUB -> "#\\d+"
            TicketFormat.SHORTCUT -> "sc-\\d+"
            TicketFormat.CUSTOM -> null
        }
    }

    private fun buildProjectPrefix(): String {
        val projects = ticketProjects?.takeIf { it.isNotEmpty() } ?: return ANY_PROJECT_KEY
        val escaped = projects.map { escapeProjectKey(it) }
        return if (escaped.size == 1) escaped.first() else "(?:${escaped.joinToString("|")})"
    }

    companion object {
        private const val ANY_PROJECT_KEY = "[A-Z]+"
        private const val DEFAULT_TASK_ID_PATTERN = "$ANY_PROJECT_KEY-\\d+"
        private const val DEFAULT_CHANGELOG_PREFIX = "CHANGELOG:"
        private const val DEFAULT_MR_TASK_SOURCE = "title"
        private val VALID_MR_TASK_SOURCES = setOf("title", "description", "both")

        /** Regex metacharacters that need escaping in project keys. */
        private val REGEX_META = Regex("""[\\^$.|?*+(){\[}]""")

        /** Escapes regex metacharacters without the JVM `\Q..\E` wrapper. */
        private fun escapeProjectKey(key: String): String = REGEX_META.replace(key) { "\\${it.value}" }
    }
}
