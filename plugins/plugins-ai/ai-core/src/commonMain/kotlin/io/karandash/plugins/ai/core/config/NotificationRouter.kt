package io.karandash.plugins.ai.core.config

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlinx.serialization.Serializable

/**
 * Notification routing: maps finding characteristics to notification targets.
 *
 * Decouples "what was found" from "who gets told". Each [NotificationRoute] specifies
 * match criteria and one or more targets. A finding that matches multiple routes is
 * delivered to all matched targets.
 */
object NotificationRouter {
    /**
     * Routes [findings] through [routes] and returns one [RoutedNotification] per
     * (finding, target) pair where the finding matches the route's criteria.
     *
     * @param findings The list of findings to route.
     * @param routes Routing rules to evaluate against each finding.
     * @return Flat list of routed notifications (may contain duplicates if routes overlap).
     */
    fun route(
        findings: List<Finding>,
        routes: List<NotificationRoute>
    ): List<RoutedNotification> {
        val notifications = mutableListOf<RoutedNotification>()
        for (finding in findings) {
            for (routeConfig in routes) {
                if (matchesRoute(finding, routeConfig.match)) {
                    for (target in routeConfig.targets) {
                        notifications.add(RoutedNotification(finding = finding, target = target))
                    }
                }
            }
        }
        return notifications
    }

    private fun matchesRoute(
        finding: Finding,
        match: RouteMatch
    ): Boolean {
        if (match.rule != null && !finding.rule.contains(match.rule)) return false
        if (match.severities.isNotEmpty()) {
            val matchSeverities = match.severities
                .mapNotNull { s -> Severity.entries.firstOrNull { it.name.equals(s, ignoreCase = true) } }
                .toSet()
            if (finding.severity !in matchSeverities) return false
        }
        return true
    }
}

/**
 * A single routing rule: if [match] criteria are satisfied, deliver to all [targets].
 *
 * @param match Criteria to evaluate against a finding.
 * @param targets Notification targets to deliver to when the finding matches.
 */
@Serializable
data class NotificationRoute(
    val match: RouteMatch,
    val targets: List<NotificationTarget>,
)

/**
 * Match criteria for a notification route.
 *
 * All specified criteria must match (AND semantics).
 * Omitted fields are treated as wildcards.
 *
 * @param rule Substring that must appear in [Finding.rule]. Null means any rule.
 * @param severities Allowed severity names (case-insensitive). Empty means any severity.
 */
@Serializable
data class RouteMatch(
    val rule: String? = null,
    val severities: List<String> = emptyList(),
)

/**
 * A notification destination.
 *
 * @param channel Channel type identifier (e.g. "slack", "email", "webhook").
 * @param target Channel-specific address (e.g. Slack channel name, email address).
 * @param url Optional URL override for webhook-style channels.
 */
@Serializable
data class NotificationTarget(
    val channel: String,
    val target: String = "",
    val url: String? = null,
)

/**
 * A finding paired with the notification target it should be delivered to.
 *
 * @param finding The finding that triggered this notification.
 * @param target The target to notify.
 */
@Serializable
data class RoutedNotification(
    val finding: Finding,
    val target: NotificationTarget
)
