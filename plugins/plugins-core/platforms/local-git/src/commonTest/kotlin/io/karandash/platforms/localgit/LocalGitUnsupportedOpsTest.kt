package io.karandash.platforms.localgit

import io.karandash.api.platform.FetchOptions
import io.karandash.api.platform.MergeRequestField
import io.karandash.api.platform.PlatformOperation
import io.karandash.models.DiffRefs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalGitUnsupportedOpsTest {
    private val provider = LocalGitProvider()

    @Test
    fun `unavailable fields cover ci conflicts approvals and reviewers`() {
        assertEquals(
            setOf(
                MergeRequestField.CI_STATUS,
                MergeRequestField.CI_JOBS,
                MergeRequestField.CONFLICTS,
                MergeRequestField.APPROVALS,
                MergeRequestField.REVIEWERS,
                MergeRequestField.LABELS
            ),
            provider.unavailableMergeRequestFields(),
        )
    }

    @Test
    fun `mutations are unsupported`() =
        runTest {
            assertFailsWith<UnsupportedOperationException> { provider.addLabels("p", "1", listOf("a")) }
            assertFailsWith<UnsupportedOperationException> { provider.removeLabels("p", "1", listOf("a")) }
            assertFailsWith<UnsupportedOperationException> { provider.setDraft("p", "1", true) }
            assertFailsWith<UnsupportedOperationException> { provider.updateTitle("p", "1", "t") }
            assertFailsWith<UnsupportedOperationException> { provider.updateDescription("p", "1", "d") }
            assertFailsWith<UnsupportedOperationException> { provider.approve("p", "1") }
            assertFailsWith<UnsupportedOperationException> { provider.unapprove("p", "1") }
        }

    @Test
    fun `comments are unsupported and lookup finds nothing`() =
        runTest {
            assertFailsWith<UnsupportedOperationException> { provider.postComment("p", "1", "b") }
            assertFailsWith<UnsupportedOperationException> { provider.updateComment("p", "1", "c", "b") }
            assertFailsWith<UnsupportedOperationException> {
                provider.postInlineComment("p", "1", "b", "f.kt", 1, DiffRefs("a", "b", "c"), null)
            }
            assertNull(provider.findExistingComment("p", "1", "marker"))
        }

    @Test
    fun `fetch by url and by id with options build a local merge request`() =
        runTest {
            val options = FetchOptions(contextLines = 1)
            assertEquals("local", provider.fetchMergeRequestByUrl("https://example.invalid/x").id)
            assertEquals("local", provider.fetchMergeRequestByUrl("https://example.invalid/x", options).id)
            assertEquals("no-such-branch-karandash", provider.fetchMergeRequest("no-such-branch-karandash", "1", options).targetBranch)
            assertTrue(provider.fetchMergeRequest("", "1").targetBranch.isNotEmpty())
        }

    @Test
    fun `only file content is declared and a local merge request has no diff refs`() =
        runTest {
            // No server-side diff to anchor inline comments: diffRefs stays null and POST_INLINE_COMMENTS is undeclared.
            assertEquals(listOf(PlatformOperation.FILE_CONTENT), PlatformOperation.entries.filter { provider.supports(it) })
            assertNull(provider.fetchMergeRequestByUrl("https://example.invalid/x").diffRefs)
        }
}
