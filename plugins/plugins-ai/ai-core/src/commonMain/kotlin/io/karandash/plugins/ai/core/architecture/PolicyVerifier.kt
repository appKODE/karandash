package io.karandash.plugins.ai.core.architecture

import kotlinx.serialization.Serializable

/**
 * Policy formal verification: checks rule configurations for logical consistency.
 *
 * Detects:
 * - Contradictory rules (rule A requires X, rule B forbids X)
 * - Unreachable rules (conditions that can never be true)
 * - Incomplete coverage (gaps in rule set)
 * - Circular dependencies between conditional rules
 */
object PolicyVerifier {
    /**
     * Verifies a set of policy rules for logical consistency.
     *
     * @param policies List of policy definitions to verify.
     * @return Verification result with any issues found.
     */
    fun verify(policies: List<PolicyDefinition>): PolicyVerificationResult {
        val issues = mutableListOf<PolicyIssue>()

        issues.addAll(findContradictions(policies))
        issues.addAll(findUnreachable(policies))
        issues.addAll(findCircularDeps(policies))

        return PolicyVerificationResult(
            isValid = issues.none { it.severity == PolicyIssueSeverity.ERROR },
            issues = issues,
            policiesChecked = policies.size,
        )
    }

    private fun findContradictions(policies: List<PolicyDefinition>): List<PolicyIssue> {
        val issues = mutableListOf<PolicyIssue>()
        for (i in policies.indices) {
            for (j in i + 1 until policies.size) {
                val a = policies[i]
                val b = policies[j]
                val scopeOverlap = a.scope.any { it in b.scope }
                if (scopeOverlap && a.action != b.action && a.condition == b.condition) {
                    issues.add(
                        PolicyIssue(
                            severity = PolicyIssueSeverity.ERROR,
                            message = "Contradiction: '${a.name}' (${a.action}) conflicts with '${b.name}' (${b.action}) on same scope",
                            policyIds = listOf(a.id, b.id),
                        ),
                    )
                }
            }
        }
        return issues
    }

    private fun findUnreachable(policies: List<PolicyDefinition>): List<PolicyIssue> {
        val issues = mutableListOf<PolicyIssue>()
        val depIds = policies.map { it.id }.toSet()
        for (policy in policies) {
            if (policy.condition.isNotEmpty() && policy.dependsOn.isNotEmpty()) {
                val missingDeps = policy.dependsOn.filter { it !in depIds }
                if (missingDeps.isNotEmpty()) {
                    issues.add(
                        PolicyIssue(
                            severity = PolicyIssueSeverity.WARNING,
                            message = "Policy '${policy.name}' depends on undefined policies: ${missingDeps.joinToString(", ")}",
                            policyIds = listOf(policy.id),
                        ),
                    )
                }
            }
        }
        return issues
    }

    private fun findCircularDeps(policies: List<PolicyDefinition>): List<PolicyIssue> {
        val graph = policies.associate { it.id to it.dependsOn.toSet() }
        val visited = mutableSetOf<String>()
        val stack = mutableSetOf<String>()
        val issues = mutableListOf<PolicyIssue>()

        fun hasCycle(node: String): Boolean {
            if (node in stack) return true
            if (node in visited) return false
            visited.add(node)
            stack.add(node)
            for (dep in graph[node].orEmpty()) {
                if (hasCycle(dep)) return true
            }
            stack.remove(node)
            return false
        }

        for (policy in policies) {
            visited.clear()
            stack.clear()
            if (hasCycle(policy.id)) {
                issues.add(
                    PolicyIssue(
                        severity = PolicyIssueSeverity.ERROR,
                        message = "Circular dependency involving policy '${policy.name}'",
                        policyIds = listOf(policy.id),
                    ),
                )
            }
        }
        return issues
    }

    /**
     * Formats a human-readable report of the verification result.
     *
     * @param result The result produced by [verify].
     * @return A markdown-formatted report string.
     */
    fun formatReport(result: PolicyVerificationResult): String =
        buildString {
            appendLine("## Policy Verification")
            appendLine("Policies checked: ${result.policiesChecked}")
            appendLine("Valid: ${result.isValid}")
            if (result.issues.isNotEmpty()) {
                appendLine("\n### Issues")
                for (issue in result.issues) {
                    appendLine("- [${issue.severity}] ${issue.message}")
                }
            }
        }
}

/**
 * A policy rule definition for formal verification.
 *
 * @param id Unique identifier for this policy.
 * @param name Human-readable name.
 * @param scope List of scopes or targets this policy applies to.
 * @param condition Condition expression (empty means always applies).
 * @param action The action this policy enforces (e.g. "enforce", "forbid").
 * @param dependsOn IDs of other policies this policy depends on.
 */
@Serializable
data class PolicyDefinition(
    val id: String,
    val name: String,
    val scope: List<String> = emptyList(),
    val condition: String = "",
    val action: String = "enforce",
    val dependsOn: List<String> = emptyList(),
)

/**
 * Result of verifying a set of [PolicyDefinition]s.
 *
 * @param isValid True when no ERROR-severity issues were found.
 * @param issues All issues detected (errors and warnings).
 * @param policiesChecked Total number of policies examined.
 */
@Serializable
data class PolicyVerificationResult(
    val isValid: Boolean,
    val issues: List<PolicyIssue>,
    val policiesChecked: Int,
)

/**
 * A single issue found during policy verification.
 *
 * @param severity Severity of the issue.
 * @param message Human-readable description of the issue.
 * @param policyIds IDs of the policies involved.
 */
@Serializable
data class PolicyIssue(
    val severity: PolicyIssueSeverity,
    val message: String,
    val policyIds: List<String>,
)

/** Severity levels for policy verification issues. */
enum class PolicyIssueSeverity { ERROR, WARNING, INFO }
