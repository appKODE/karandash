package io.karandash.profiles

import io.karandash.api.ModuleType
import io.karandash.api.context.ConditionContext
import io.karandash.conditions.DefaultPolicyEngine
import io.karandash.conditions.MutablePredicateRegistry
import io.karandash.config.PolicyConfig
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.support.KarandashJson
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntegrationMrProfileModuleTest {
    private val module = IntegrationMrProfileModule()

    private fun mr(
        source: String,
        target: String,
        commits: Int = 1,
    ) = MergeRequest(
        id = "1",
        iid = 1,
        title = "t",
        description = null,
        author = User("dev", "Dev"),
        sourceBranch = source,
        targetBranch = target,
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        commits = List(commits) { Commit("sha$it", "m", CommitAuthor("a", "a@x"), "2026-01-01T00:00:00Z") },
        changes = emptyList(),
    )

    private suspend fun matches(mr: MergeRequest): Boolean {
        val policies = module.policies().map { KarandashJson.Default.decodeFromJsonElement(PolicyConfig.serializer(), it) }
        return DefaultPolicyEngine()
            .evaluate(policies, ConditionContext(mr = mr), MutablePredicateRegistry())
            .single()
            .matched
    }

    @Test
    fun `profile id type and no rule configs`() =
        runTest {
            assertEquals(IntegrationMrProfileModule.PROFILE_ID, module.profileId)
            assertEquals(ModuleType.PROFILE, module.type)
            assertTrue(module.ruleConfigs().isEmpty())
        }

    @Test
    fun `policy lowers the three commit rules to warning`() =
        runTest {
            val policy = KarandashJson.Default.decodeFromJsonElement(PolicyConfig.serializer(), module.policies().single())
            assertEquals(listOf("commitMessages", "commitBody", "contextTag"), policy.modules)
            assertEquals("warning", policy.severity)
        }

    @Test
    fun `feature or release into develop or main matches`() =
        runTest {
            assertTrue(matches(mr("feature/x", "develop")))
            assertTrue(matches(mr("release/1.2", "main")))
            assertTrue(matches(mr("feature/x", "master")))
        }

    @Test
    fun `ordinary task branch with few commits does not match`() =
        runTest {
            assertEquals(false, matches(mr("task/ABC-1", "develop")))
            assertEquals(false, matches(mr("feature/x", "feature/parent")))
        }

    @Test
    fun `more than 30 commits matches any branch`() =
        runTest {
            assertEquals(false, matches(mr("task/ABC-1", "develop", commits = 30)))
            assertTrue(matches(mr("task/ABC-1", "develop", commits = 31)))
        }
}
