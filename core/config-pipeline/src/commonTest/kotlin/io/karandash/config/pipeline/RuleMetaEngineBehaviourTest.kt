package io.karandash.config.pipeline

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.module.RuleModule
import io.karandash.config.getRuleConfigElements
import io.karandash.engine.ValidationEngine
import io.karandash.engine.ValidationResult
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Raw `rules.<rule>.<meta key>` config, parsed by the real [ConfigMapParser], must change what
 * [ValidationEngine] does. Every case has a twin without the key so the contrast is explicit.
 */
class RuleMetaEngineBehaviourTest {
    private class Run(
        val order: List<String>,
        val result: ValidationResult,
    ) {
        val skipped: Set<String> get() = result.skippedRules.map { it.ruleId }.toSet()
    }

    private class Case(
        val name: String,
        val rules: Map<String, Any?>,
        val draft: Boolean = false,
        val check: (Run) -> Unit,
    )

    private class Stub(
        override val id: String,
        private val finding: Finding,
        private val order: MutableList<String>,
    ) : RuleModule {
        override val name: String = "Stub $id"
        override val version: String = "1.0"
        override val dslName: String = id

        override suspend fun contributedRules(): Set<String> = setOf(id)

        override suspend fun initialize(ctx: PluginContext): Boolean = true

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ): List<Finding> {
            order.add(id)
            return listOf(finding)
        }
    }

    private fun mr(draft: Boolean) =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "feat: add feature",
            description = null,
            author = User(username = "alice", name = "Alice"),
            sourceBranch = "feature/x",
            targetBranch = "main",
            state = MergeRequestState.OPENED,
            draft = draft,
            labels = emptyList(),
            commits = emptyList(),
            changes = emptyList(),
            approvals = emptyList(),
            reviewers = emptyList(),
        )

    private suspend fun execute(case: Case): Run {
        val config = ConfigMapParser.parseFullConfig(mapOf("rules" to case.rules)).config
        val order = mutableListOf<String>()
        val registry = ModuleRegistryImpl()
        for (ruleId in config.rules.ruleConfigs.keys) {
            val finding =
                Finding(
                    rule = ruleId,
                    message = "found",
                    severity = if (ruleId == "blocker") Severity.ERROR else Severity.WARNING,
                    suggestion = "default suggestion",
                    explanation = "default explanation",
                    location = Location.FileRef("generated/Api.kt"),
                )
            registry.register(Stub(ruleId, finding, order))
        }
        val engine =
            ValidationEngine(
                config = config,
                registry = registry,
                configExtractor = { cfg, ruleId -> cfg.rules.getRuleConfigElements(ruleId) },
            )
        return Run(order, engine.validate(mr(case.draft)))
    }

    private val cases =
        listOf(
            Case(
                "bail-on-fail on a failing rule skips lower priority rules",
                mapOf(
                    "blocker" to mapOf("priority" to 1, "bail-on-fail" to true),
                    "later" to mapOf("priority" to 10),
                ),
            ) { run ->
                assertEquals(setOf("later"), run.skipped)
                assertEquals(listOf("blocker"), run.order)
            },
            Case(
                "without bail-on-fail lower priority rules still run",
                mapOf(
                    "blocker" to mapOf("priority" to 1),
                    "later" to mapOf("priority" to 10),
                ),
            ) { run ->
                assertEquals(emptySet(), run.skipped)
                assertEquals(listOf("blocker", "later"), run.order)
            },
            Case(
                "priority runs lower numbers first",
                mapOf(
                    "alpha" to mapOf("priority" to 50),
                    "zeta" to mapOf("priority" to 5),
                ),
            ) { run -> assertEquals(listOf("zeta", "alpha"), run.order) },
            Case(
                // same-priority rules run in parallel, so the contrast swaps the priorities instead of dropping them
                "swapped priorities swap the run order",
                mapOf(
                    "alpha" to mapOf("priority" to 5),
                    "zeta" to mapOf("priority" to 50),
                ),
            ) { run -> assertEquals(listOf("alpha", "zeta"), run.order) },
            Case(
                "fallback rule is skipped when its concern-area is covered",
                mapOf(
                    "covering" to mapOf("concern-area" to "naming"),
                    "fallback" to mapOf("mode" to "fallback", "concern-area" to "naming"),
                ),
            ) { run ->
                assertEquals(setOf("fallback"), run.skipped)
                assertEquals(listOf("covering"), run.order)
            },
            Case(
                "fallback rule runs when its concern-area differs",
                mapOf(
                    "covering" to mapOf("concern-area" to "naming"),
                    "fallback" to mapOf("mode" to "fallback", "concern-area" to "security"),
                ),
            ) { run ->
                assertEquals(emptySet(), run.skipped)
                assertEquals(listOf("covering", "fallback"), run.order)
            },
            Case(
                "supplement mode defers a high priority rule to pass 2",
                mapOf(
                    "supp" to mapOf("mode" to "supplement", "priority" to 1),
                    "norm" to mapOf("priority" to 50),
                ),
            ) { run -> assertEquals(listOf("norm", "supp"), run.order) },
            Case(
                "without mode the same high priority rule runs first",
                mapOf(
                    "supp" to mapOf("priority" to 1),
                    "norm" to mapOf("priority" to 50),
                ),
            ) { run -> assertEquals(listOf("supp", "norm"), run.order) },
            Case(
                "explanation and suggestion override the rule defaults",
                mapOf("r" to mapOf("explanation" to "why", "suggestion" to "do this")),
            ) { run ->
                assertEquals(listOf("why"), run.result.findings.map { it.explanation })
                assertEquals(listOf("do this"), run.result.findings.map { it.suggestion })
            },
            Case(
                "without explanation and suggestion the rule defaults stay",
                mapOf("r" to mapOf("x" to 1)),
            ) { run ->
                assertEquals(listOf("default explanation"), run.result.findings.map { it.explanation })
                assertEquals(listOf("default suggestion"), run.result.findings.map { it.suggestion })
            },
            Case("severity error forces every finding to error", mapOf("r" to mapOf("severity" to "error"))) { run ->
                assertEquals(listOf(Severity.ERROR), run.result.findings.map { it.severity })
            },
            Case("without severity the rule keeps its own", mapOf("r" to mapOf("x" to 1))) { run ->
                assertEquals(listOf(Severity.WARNING), run.result.findings.map { it.severity })
            },
            Case("exclude-paths drops findings in matching files", mapOf("r" to mapOf("exclude-paths" to listOf("generated/**")))) { run ->
                assertEquals(listOf("r"), run.order)
                assertEquals(emptyList(), run.result.findings)
            },
            Case("exclude-paths keeps findings in other files", mapOf("r" to mapOf("exclude-paths" to listOf("docs/**")))) { run ->
                assertEquals(1, run.result.findings.size)
            },
            Case("when is_draft skips the rule on a non-draft request", mapOf("r" to mapOf("when" to "is_draft"))) { run ->
                assertEquals(setOf("r"), run.skipped)
                assertEquals(emptyList(), run.order)
            },
            Case(
                "when is_draft runs the rule on a draft request",
                mapOf("r" to mapOf("when" to "is_draft")),
                draft = true,
            ) { run ->
                assertEquals(emptySet(), run.skipped)
                assertEquals(listOf("r"), run.order)
            },
        )

    @Test
    fun `rule meta keys from raw config change what the engine does`() =
        runTest {
            for (case in cases) {
                try {
                    case.check(execute(case))
                } catch (e: AssertionError) {
                    throw AssertionError("case '${case.name}' failed: ${e.message}", e)
                }
            }
        }
}
