package io.karandash.conditions

import io.karandash.api.context.ConditionContext
import io.karandash.cache.CacheManager
import io.karandash.cache.CacheSpec
import io.karandash.config.PolicyConfig
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Regressions for the condition engine. */
class ConditionsRegressionTest {
    private val mr = MergeRequest(
        id = "1",
        iid = 1,
        title = "feat: add",
        description = null,
        author = User(username = "alice", name = "alice"),
        sourceBranch = "feature/x",
        targetBranch = "main",
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        commits = emptyList(),
        changes = emptyList(),
        approvals = emptyList(),
        reviewers = emptyList(),
    )

    @AfterTest
    fun reset() = BuiltinPredicates.useManaged(CacheManager.disabled())

    @Test
    fun `policy condition regexes are compiled into the run managed cache`() =
        runTest {
            val cacheManager = CacheManager.disabled()
            BuiltinPredicates.useManaged(cacheManager)

            val result = DefaultPolicyEngine().evaluate(
                policies = listOf(PolicyConfig(name = "p", condition = "branch_matches(\"^feature/\")", modules = listOf("m"))),
                context = ConditionContext(mr = mr),
                predicates = MutablePredicateRegistry(),
            )

            assertTrue(result.single().matched)
            val cache = cacheManager.getOrCreate<String, Regex>(ExpressionEvaluator.CACHE_NAME, CacheSpec(maxEntries = 1))
            assertNotNull(cache.getMemory("^feature/"), "branch_matches compiled its regex outside the managed cache")
        }
}
