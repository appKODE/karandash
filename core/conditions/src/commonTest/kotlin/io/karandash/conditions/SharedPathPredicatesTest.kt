package io.karandash.conditions

import io.karandash.api.context.ConditionContext
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** `file_path_matches` has one path-glob implementation, shared by policies and rule `when:` filters. */
class SharedPathPredicatesTest {
    private fun context(vararg paths: String) =
        ConditionContext(
            mr = MergeRequest(
                id = "1",
                iid = 1,
                title = "t",
                description = null,
                author = User(username = "a", name = "A"),
                sourceBranch = "f",
                targetBranch = "main",
                state = MergeRequestState.OPENED,
                draft = false,
                labels = listOf("Bug"),
                commits = emptyList(),
                changes = paths.map { FileChange(path = it, changeType = ChangeType.MODIFIED) },
                approvals = emptyList(),
                reviewers = emptyList(),
            ),
        )

    @Test
    fun filePathMatchesUsesPathGlobSemantics() {
        val predicate = CommonPredicates.filePathMatches
        assertFalse(predicate.evaluate(listOf("src/**"), context("foo/src/x.kt")))
        assertTrue(predicate.evaluate(listOf("src/**"), context("src/a/x.kt")))
        assertTrue(predicate.evaluate(listOf("*.md"), context("README.md")))
    }

    @Test
    fun registryHoldsTheSharedInstances() {
        val registry = MutablePredicateRegistry<ConditionContext>()
        CommonPredicates.registerAll(registry)
        assertSame(CommonPredicates.filePathMatches, registry.get("file_path_matches"))
        assertSame(CommonPredicates.hasChangesIn, registry.get("has_changes_in"))
        assertSame(CommonPredicates.labelExists, registry.get("label_exists"))
        assertTrue(CommonPredicates.labelExists.evaluate(listOf("bug"), context()))
    }
}
