package io.karandash.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MrCacheKeyTest {
    private fun makeMr(
        id: String,
        iid: Int = 1,
    ) = MergeRequest(
        id = id,
        iid = iid,
        title = "Test",
        description = null,
        sourceBranch = "feature",
        targetBranch = "main",
        author = User("1", "dev", "Dev"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        commits = emptyList(),
        changes = emptyList(),
    )

    @Test
    fun buildKeyProducesMrPrefixWithId() {
        val mr = makeMr(id = "12345")
        assertEquals("mr-12345", MrCacheKey.buildKey(mr))
    }

    @Test
    fun buildKeySanitizesSpecialCharacters() {
        val mr = makeMr(id = "org/repo#42")
        assertEquals("mr-org-repo-42", MrCacheKey.buildKey(mr))
    }

    @Test
    fun buildKeyPreservesHyphensInId() {
        val mr = makeMr(id = "my-project-99")
        assertEquals("mr-my-project-99", MrCacheKey.buildKey(mr))
    }

    @Test
    fun buildKeyDoesNotIncludeIid() {
        val mr1 = makeMr(id = "100", iid = 1)
        val mr2 = makeMr(id = "100", iid = 999)
        assertEquals(MrCacheKey.buildKey(mr1), MrCacheKey.buildKey(mr2))
    }

    @Test
    fun buildKeyThrowsOnBlankId() {
        val mr = makeMr(id = "")
        assertFailsWith<IllegalArgumentException> {
            MrCacheKey.buildKey(mr)
        }
    }

    @Test
    fun buildKeyThrowsOnWhitespaceOnlyId() {
        val mr = makeMr(id = "   ")
        assertFailsWith<IllegalArgumentException> {
            MrCacheKey.buildKey(mr)
        }
    }
}
