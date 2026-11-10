package io.karandash.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Comment keywords and emoji reactions become approvals. */
class ApprovalSignalTest {
    private val bob = User("bob", "Bob")

    private fun comment(text: String) = ApprovalSignal(bob, ApprovalMethod.COMMENT_KEYWORD, text, "2026-10-20T10:00:00Z")

    @Test
    fun `a keyword comment is an approval carrying its method and time`() {
        val approval = checkNotNull(comment("  LGTM! ").toApproval())

        assertEquals(Approval(bob, "2026-10-20T10:00:00Z", ReviewState.APPROVED, ApprovalMethod.COMMENT_KEYWORD), approval)
    }

    @Test
    fun `keywords match the whole comment ignoring case`() {
        assertNull(comment("not LGTM yet").toApproval())
        assertEquals(ApprovalMethod.COMMENT_KEYWORD, comment("Approved").toApproval()?.method)
        assertNull(comment("LGTM").toApproval(keywords = listOf("ship it")))
    }

    @Test
    fun `a listed reaction is an approval`() {
        val thumbsUp = ApprovalSignal(bob, ApprovalMethod.EMOJI_REACTION, ":thumbsup:")

        assertEquals(ApprovalMethod.EMOJI_REACTION, thumbsUp.toApproval()?.method)
        assertNull(ApprovalSignal(bob, ApprovalMethod.EMOJI_REACTION, "eyes").toApproval())
        assertNull(ApprovalSignal(bob, ApprovalMethod.EMOJI_REACTION, "+1").toApproval(emojis = listOf("heart")))
    }
}
