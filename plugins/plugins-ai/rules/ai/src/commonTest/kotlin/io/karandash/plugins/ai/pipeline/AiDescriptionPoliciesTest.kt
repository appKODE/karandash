package io.karandash.plugins.ai.pipeline

import io.karandash.models.Approval
import io.karandash.models.MergeRequest
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.config.GracePeriodWindowConfig
import io.karandash.plugins.ai.core.config.InlineOverridesConfig
import io.karandash.plugins.ai.core.config.ViolationBaselineConfig
import io.karandash.sdk.Logger
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class AiDescriptionPoliciesTest {
    private val warnings = mutableListOf<String>()
    private val logger = object : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }
    private val now = Instant.parse("2026-03-16T12:00:00Z")
    private val policies = AiDescriptionPolicies(logger) { now }

    private fun finding(
        rule: String = "ai-size-limits",
        severity: Severity = Severity.ERROR,
        path: String? = "src/A.kt",
    ) = Finding(severity = severity, message = "m", rule = rule, location = path?.let { Location.FileRef(it, 1) })

    private fun mr(
        description: String,
        approvedAt: List<String?> = emptyList(),
    ): MergeRequest =
        createTestMergeRequest(
            description = description,
            approvals = approvedAt.mapIndexed { i, at -> Approval(User(username = "r$i", name = "R$i"), approvedAt = at) },
        )

    @Test
    fun `override waive and exempt tags downgrade the named rule only`() {
        val description = """
            <!-- @karandash override ai-size-limits: Large refactor necessary -->
            <!-- @karandash waive ai-security-scan: False positive -->
        """.trimIndent()
        val result = policies.applyInlineOverrides(
            listOf(finding(), finding(rule = "ai-security-scan"), finding(rule = "other")),
            mr(description),
            InlineOverridesConfig(auditLog = true),
        )
        assertEquals(listOf(Severity.INFO, Severity.INFO, Severity.ERROR), result.map { it.severity })
        assertEquals("[OVERRIDDEN: Large refactor necessary] m", result[0].message)
        assertEquals("Large refactor necessary", result[0].metadata["overrideReason"])
    }

    @Test
    fun `tags need the block and an approval when requireApproval is set`() {
        val description = "<!-- @karandash override ai-size-limits: why -->"
        assertEquals(Severity.ERROR, policies.applyInlineOverrides(listOf(finding()), mr(description), null).single().severity)
        val gated = policies.applyInlineOverrides(listOf(finding()), mr(description), InlineOverridesConfig(requireApproval = true))
        assertEquals(Severity.ERROR, gated.single().severity)
        val approved = policies.applyInlineOverrides(
            listOf(finding()),
            mr(description, approvedAt = listOf(null)),
            InlineOverridesConfig(requireApproval = true),
        )
        assertEquals(Severity.INFO, approved.single().severity)
    }

    @Test
    fun `enabled false turns the legacy override form off too`() {
        val description = """<!-- karandash-override rule: ai-size-limits reason: "x" -->"""
        assertEquals(Severity.INFO, policies.applyInlineOverrides(listOf(finding()), mr(description), null).single().severity)
        val off = policies.applyInlineOverrides(listOf(finding()), mr(description), InlineOverridesConfig(enabled = false))
        assertEquals(Severity.ERROR, off.single().severity)
    }

    @Test
    fun `grace window downgrades everything once approved and before expiry`() {
        val start = "<!-- @karandash grace-period-start: Emergency hotfix -->"
        val config = GracePeriodWindowConfig(durationMinutes = 60, approvalRequired = 2)
        val fresh = listOf("2026-03-16T11:00:00Z", "2026-03-16T11:30:00Z")
        assertEquals(Severity.INFO, policies.applyGraceWindow(listOf(finding()), mr(start, fresh), config).single().severity)
        assertEquals(Severity.ERROR, policies.applyGraceWindow(listOf(finding()), mr(start, fresh.take(1)), config).single().severity)
        val stale = listOf("2026-03-16T09:00:00Z", "2026-03-16T10:00:00Z")
        assertEquals(Severity.ERROR, policies.applyGraceWindow(listOf(finding()), mr(start, stale), config).single().severity)
        assertEquals(3, warnings.size) // active (audit), too few approvals, expired
    }

    @Test
    fun `overrideTag names the tag that downgrades a rule`() {
        val description = "<!-- @karandash accept ai-size-limits: why -->"
        assertEquals(
            Severity.ERROR,
            policies.applyInlineOverrides(listOf(finding()), mr(description), InlineOverridesConfig()).single().severity
        )
        val custom = policies.applyInlineOverrides(listOf(finding()), mr(description), InlineOverridesConfig(overrideTag = "accept"))
        assertEquals(Severity.INFO, custom.single().severity)
    }

    @Test
    fun `gracePeriod auditLog false stays silent while the window still downgrades`() {
        val start = "<!-- @karandash grace-period-start: Emergency hotfix -->"
        val config = GracePeriodWindowConfig(durationMinutes = 60, approvalRequired = 2, auditLog = false)
        val fresh = listOf("2026-03-16T11:00:00Z", "2026-03-16T11:30:00Z")
        val before = warnings.size
        assertEquals(Severity.INFO, policies.applyGraceWindow(listOf(finding()), mr(start, fresh), config).single().severity)
        assertEquals(before, warnings.size)
    }

    @Test
    fun `grace period end tag closes the window`() {
        val description = "<!-- @karandash grace-period-start: x -->\n<!-- @karandash grace-period-end -->"
        val result = policies.applyGraceWindow(listOf(finding()), mr(description), GracePeriodWindowConfig())
        assertEquals(Severity.ERROR, result.single().severity)
    }

    @Test
    fun `missing baseline file is an empty baseline so findings stay`() {
        val config = ViolationBaselineConfig(baselineFile = "build/does-not-exist/violation-baseline.json")
        assertEquals(Severity.ERROR, policies.applyBaselineBudget(listOf(finding()), config).single().severity)
    }
}
