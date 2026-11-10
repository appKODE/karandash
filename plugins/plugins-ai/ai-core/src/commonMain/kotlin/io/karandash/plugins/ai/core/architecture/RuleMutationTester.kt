package io.karandash.plugins.ai.core.architecture

import io.karandash.api.support.rethrowCancellation
import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.normalize.ModuleIdNormalizer
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * Rule mutation testing: generates synthetic bad MRs to test rule coverage.
 *
 * Creates MRs with known violations and runs the validator to check if rules
 * catch them. Mutations that escape detection indicate gaps in rule configuration.
 */
object RuleMutationTester {
    /**
     * Generates a set of mutation test cases.
     *
     * Each mutation targets a specific rule category and should be caught
     * by a well-configured rule set.
     *
     * @return List of mutation test cases.
     */
    fun generateMutations(): List<MutationTestCase> =
        listOf(
            MutationTestCase(
                name = "oversized-mr",
                description = "MR with 1000+ lines changed",
                targetRule = "size-limits",
                mr = buildMr(
                    title = "feat: massive refactor",
                    changes = (1..OVERSIZED_FILE_COUNT).map { i ->
                        FileChange("src/File$i.kt", ChangeType.MODIFIED, additions = LINES_PER_FILE, deletions = LINES_PER_FILE)
                    },
                ),
            ),
            MutationTestCase(
                name = "bad-title-format",
                description = "MR title not matching conventional commits",
                targetRule = "title-format",
                mr = buildMr(title = "fixed some stuff"),
            ),
            MutationTestCase(
                name = "no-description",
                description = "MR with empty description",
                targetRule = "description-format",
                mr = buildMr(title = GENERIC_FEAT_TITLE, description = EMPTY_DESCRIPTION),
            ),
            MutationTestCase(
                name = "draft-mr",
                description = "Draft MR submitted for review",
                targetRule = "draft",
                mr = buildMr(title = "WIP: work in progress", draft = true),
            ),
            MutationTestCase(
                name = "secret-in-diff",
                description = "API key leaked in diff",
                targetRule = "security",
                mr = buildMr(
                    title = "feat: add config",
                    changes = listOf(
                        FileChange(
                            "src/config.kt",
                            ChangeType.MODIFIED,
                            additions = 1,
                            diffContent = "+val apiKey = \"sk-${"a".repeat(API_KEY_LENGTH)}\"",
                        ),
                    ),
                ),
            ),
            MutationTestCase(
                name = "no-jira-ticket",
                description = "MR without JIRA ticket reference",
                targetRule = "jira-ticket",
                mr = buildMr(title = GENERIC_FEAT_TITLE, description = "No ticket reference here"),
            ),
            MutationTestCase(
                name = "wrong-branch-name",
                description = "Branch name not following convention",
                targetRule = "branch-naming",
                mr = buildMr(title = GENERIC_FEAT_TITLE, sourceBranch = "my-branch"),
            ),
        )

    /**
     * Runs mutation tests against a validator function.
     *
     * @param mutations Cases to run; defaults to [generateMutations].
     * @param validator Function that validates an MR and returns findings.
     * @return Mutation test results showing which mutations were caught vs escaped.
     */
    fun runTests(
        mutations: List<MutationTestCase> = generateMutations(),
        validator: (MergeRequest) -> List<Finding>,
    ): MutationTestResult {
        val results = mutations.map { mutation ->
            @Suppress("TooGenericExceptionCaught")
            val findings = try {
                validator(mutation.mr)
            } catch (e: Exception) {
                // boundary: validator runs configured rule modules (plugin code)
                e.rethrowCancellation()
                emptyList()
            }
            // Targets are kebab-case ("size-limits") while rule IDs are camelCase ("sizeLimits").
            val target = ModuleIdNormalizer.normalize(mutation.targetRule)
            val caught = findings.any { ModuleIdNormalizer.normalize(it.rule).contains(target) }
            MutationOutcome(
                mutation = mutation,
                caught = caught,
                findingCount = findings.size,
                matchedRules = findings.map { it.rule }.distinct(),
            )
        }

        val caughtCount = results.count { it.caught }

        return MutationTestResult(
            outcomes = results,
            totalMutations = mutations.size,
            caughtCount = caughtCount,
            escapedCount = mutations.size - caughtCount,
            mutationScore = if (mutations.isNotEmpty()) {
                caughtCount.toDouble() / mutations.size
            } else {
                1.0
            },
        )
    }

    /**
     * Formats a human-readable report of the mutation test result.
     *
     * @param result The result produced by [runTests].
     * @return A markdown-formatted report string.
     */
    fun formatReport(result: MutationTestResult): String =
        buildString {
            appendLine("## Rule Mutation Testing")
            appendLine(
                "Mutation score: ${(result.mutationScore * PERCENT).roundToInt()}% (${result.caughtCount}/${result.totalMutations} caught)"
            )
            appendLine()
            for (outcome in result.outcomes) {
                val status = if (outcome.caught) "CAUGHT" else "ESCAPED"
                appendLine("- [$status] ${outcome.mutation.name}: ${outcome.mutation.description}")
                if (!outcome.caught) {
                    appendLine("  Target rule: ${outcome.mutation.targetRule} — NOT TRIGGERED")
                }
            }
        }

    private fun buildMr(
        title: String,
        description: String? = null,
        sourceBranch: String = "feature/test",
        draft: Boolean = false,
        changes: List<FileChange> = emptyList(),
    ): MergeRequest =
        MergeRequest(
            id = "mutation-test",
            iid = 1,
            title = title,
            description = description,
            sourceBranch = sourceBranch,
            targetBranch = "main",
            author = User(username = "tester", name = "Mutation Tester"),
            state = MergeRequestState.OPENED,
            draft = draft,
            labels = emptyList(),
            commits = listOf(
                Commit("abc123", title, CommitAuthor("Tester", "test@example.com"), "2026-01-01T00:00:00Z"),
            ),
            changes = changes,
        )

    private const val OVERSIZED_FILE_COUNT = 20
    private const val LINES_PER_FILE = 50
    private const val API_KEY_LENGTH = 48
    private const val PERCENT = 100
    private const val EMPTY_DESCRIPTION = ""
    private const val GENERIC_FEAT_TITLE = "feat: add feature"
}

/**
 * A single mutation test case targeting a specific rule.
 *
 * @param name Short identifier for this mutation.
 * @param description Human-readable description of the violation.
 * @param targetRule Rule ID (or substring) expected to catch this mutation.
 * @param mr The synthetic MR with the injected violation.
 */
@Serializable
data class MutationTestCase(
    val name: String,
    val description: String,
    val targetRule: String,
    val mr: MergeRequest,
)

/**
 * Outcome of running a single mutation test case.
 *
 * @param mutation The test case that was executed.
 * @param caught Whether the target rule fired for this mutation.
 * @param findingCount Total findings returned by the validator.
 * @param matchedRules Distinct rule IDs from all returned findings.
 */
@Serializable
data class MutationOutcome(
    val mutation: MutationTestCase,
    val caught: Boolean,
    val findingCount: Int,
    val matchedRules: List<String>,
)

/**
 * Aggregated result of a full mutation test run.
 *
 * @param outcomes Per-mutation outcomes.
 * @param totalMutations Total number of mutations tested.
 * @param caughtCount Mutations caught by rules.
 * @param escapedCount Mutations not caught by any rule.
 * @param mutationScore Fraction of mutations caught (0.0–1.0).
 */
@Serializable
data class MutationTestResult(
    val outcomes: List<MutationOutcome>,
    val totalMutations: Int,
    val caughtCount: Int,
    val escapedCount: Int,
    val mutationScore: Double,
)
