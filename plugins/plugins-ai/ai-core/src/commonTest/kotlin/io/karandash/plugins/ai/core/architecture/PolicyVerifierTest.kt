package io.karandash.plugins.ai.core.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolicyVerifierTest {
    @Test
    fun noPoliciesIsValid() {
        val result = PolicyVerifier.verify(emptyList())
        assertTrue(result.isValid)
        assertTrue(result.issues.isEmpty())
        assertEquals(0, result.policiesChecked)
    }

    @Test
    fun contradictionDetected() {
        val a = PolicyDefinition(id = "p1", name = "Enforce SSL", scope = listOf("ssl"), action = "enforce")
        val b = PolicyDefinition(id = "p2", name = "Forbid SSL", scope = listOf("ssl"), action = "forbid")
        val result = PolicyVerifier.verify(listOf(a, b))
        assertFalse(result.isValid)
        val errors = result.issues.filter { it.severity == PolicyIssueSeverity.ERROR }
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.any { it.policyIds.containsAll(listOf("p1", "p2")) })
    }

    @Test
    fun missingDependencyWarned() {
        val p = PolicyDefinition(
            id = "p1",
            name = "Conditional Policy",
            condition = "some-condition",
            dependsOn = listOf("missing-id"),
        )
        val result = PolicyVerifier.verify(listOf(p))
        val warnings = result.issues.filter { it.severity == PolicyIssueSeverity.WARNING }
        assertTrue(warnings.isNotEmpty())
        assertTrue(warnings.any { it.message.contains("missing-id") })
    }

    @Test
    fun circularDependencyIsError() {
        val a = PolicyDefinition(id = "p1", name = "Policy A", dependsOn = listOf("p2"))
        val b = PolicyDefinition(id = "p2", name = "Policy B", dependsOn = listOf("p1"))
        val result = PolicyVerifier.verify(listOf(a, b))
        assertFalse(result.isValid)
        val errors = result.issues.filter { it.severity == PolicyIssueSeverity.ERROR }
        assertTrue(errors.isNotEmpty())
    }

    @Test
    fun formatReportReadable() {
        val a = PolicyDefinition(id = "p1", name = "Enforce SSL", scope = listOf("ssl"), action = "enforce")
        val b = PolicyDefinition(id = "p2", name = "Forbid SSL", scope = listOf("ssl"), action = "forbid")
        val result = PolicyVerifier.verify(listOf(a, b))
        val report = PolicyVerifier.formatReport(result)
        assertTrue(report.contains("Policy Verification"))
        assertTrue(report.contains("Policies checked: 2"))
        assertTrue(report.contains("Issues"))
    }

    @Test
    fun singlePolicyNoDepsIsValid() {
        val p = PolicyDefinition(id = "p1", name = "Only Policy", scope = listOf("scope-a"), action = "enforce")
        val result = PolicyVerifier.verify(listOf(p))
        assertTrue(result.isValid)
        assertTrue(result.issues.isEmpty())
        assertEquals(1, result.policiesChecked)
    }

    @Test
    fun noContradictionWhenScopesDiffer() {
        val a = PolicyDefinition(id = "p1", name = "Enforce X", scope = listOf("scope-a"), action = "enforce")
        val b = PolicyDefinition(id = "p2", name = "Forbid Y", scope = listOf("scope-b"), action = "forbid")
        val result = PolicyVerifier.verify(listOf(a, b))
        assertTrue(result.isValid)
    }
}
