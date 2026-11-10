package io.karandash.plugins.ai.core.config

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationRouterTest {
    private fun finding(
        rule: String,
        severity: Severity = Severity.ERROR
    ) = Finding(severity = severity, message = "test", rule = rule)

    @Test
    fun `empty routes returns empty`() {
        val findings = listOf(finding("title-format"))
        val result = NotificationRouter.route(findings, emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `empty findings returns empty`() {
        val routes = listOf(
            NotificationRoute(
                match = RouteMatch(rule = "title-format"),
                targets = listOf(NotificationTarget(channel = "slack", target = "#alerts")),
            ),
        )
        val result = NotificationRouter.route(emptyList(), routes)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `rule match routes finding to target`() {
        val f = finding("title-format")
        val target = NotificationTarget(channel = "slack", target = "#alerts")
        val routes = listOf(
            NotificationRoute(match = RouteMatch(rule = "title-format"), targets = listOf(target)),
        )
        val result = NotificationRouter.route(listOf(f), routes)
        assertEquals(1, result.size)
        assertEquals(f, result[0].finding)
        assertEquals(target, result[0].target)
    }

    @Test
    fun `rule partial match routes finding`() {
        val f = finding("rules:title-format:pattern")
        val target = NotificationTarget(channel = "email", target = "team@example.com")
        val routes = listOf(
            NotificationRoute(match = RouteMatch(rule = "title-format"), targets = listOf(target)),
        )
        val result = NotificationRouter.route(listOf(f), routes)
        assertEquals(1, result.size)
    }

    @Test
    fun `non-matching rule is skipped`() {
        val f = finding("commit-messages")
        val routes = listOf(
            NotificationRoute(
                match = RouteMatch(rule = "title-format"),
                targets = listOf(NotificationTarget(channel = "slack", target = "#alerts")),
            ),
        )
        val result = NotificationRouter.route(listOf(f), routes)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `severity match routes finding`() {
        val f = finding("any-rule", Severity.ERROR)
        val target = NotificationTarget(channel = "pagerduty", target = "on-call")
        val routes = listOf(
            NotificationRoute(match = RouteMatch(severities = listOf("ERROR")), targets = listOf(target)),
        )
        val result = NotificationRouter.route(listOf(f), routes)
        assertEquals(1, result.size)
        assertEquals(target, result[0].target)
    }

    @Test
    fun `severity mismatch skips finding`() {
        val f = finding("any-rule", Severity.WARNING)
        val routes = listOf(
            NotificationRoute(
                match = RouteMatch(severities = listOf("ERROR")),
                targets = listOf(NotificationTarget(channel = "slack", target = "#alerts")),
            ),
        )
        val result = NotificationRouter.route(listOf(f), routes)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `severity match is case-insensitive`() {
        val f = finding("any-rule", Severity.ERROR)
        val routes = listOf(
            NotificationRoute(
                match = RouteMatch(severities = listOf("error")),
                targets = listOf(NotificationTarget(channel = "slack", target = "#alerts")),
            ),
        )
        val result = NotificationRouter.route(listOf(f), routes)
        assertEquals(1, result.size)
    }

    @Test
    fun `multiple targets for same match produces one notification per target`() {
        val f = finding("title-format")
        val t1 = NotificationTarget(channel = "slack", target = "#dev")
        val t2 = NotificationTarget(channel = "email", target = "dev@example.com")
        val routes = listOf(
            NotificationRoute(match = RouteMatch(rule = "title-format"), targets = listOf(t1, t2)),
        )
        val result = NotificationRouter.route(listOf(f), routes)
        assertEquals(2, result.size)
        assertEquals(t1, result[0].target)
        assertEquals(t2, result[1].target)
    }

    @Test
    fun `finding matching multiple routes produces notification per route-target pair`() {
        val f = finding("title-format", Severity.ERROR)
        val t1 = NotificationTarget(channel = "slack", target = "#dev")
        val t2 = NotificationTarget(channel = "pagerduty", target = "on-call")
        val routes = listOf(
            NotificationRoute(match = RouteMatch(rule = "title-format"), targets = listOf(t1)),
            NotificationRoute(match = RouteMatch(severities = listOf("ERROR")), targets = listOf(t2)),
        )
        val result = NotificationRouter.route(listOf(f), routes)
        assertEquals(2, result.size)
    }

    @Test
    fun `null rule in match acts as wildcard`() {
        val f = finding("any-rule")
        val target = NotificationTarget(channel = "slack", target = "#all")
        val routes = listOf(
            NotificationRoute(match = RouteMatch(rule = null, severities = emptyList()), targets = listOf(target)),
        )
        val result = NotificationRouter.route(listOf(f), routes)
        assertEquals(1, result.size)
    }
}
