package io.karandash.models

import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewStateTest {
    @Test
    fun `approved maps correctly`() {
        assertEquals(ReviewState.APPROVED, ReviewState.fromPlatformState("approved"))
        assertEquals(ReviewState.APPROVED, ReviewState.fromPlatformState("approve"))
        assertEquals(ReviewState.APPROVED, ReviewState.fromPlatformState("APPROVED"))
        assertEquals(ReviewState.APPROVED, ReviewState.fromPlatformState("Approve"))
    }

    @Test
    fun `changes requested maps correctly`() {
        assertEquals(ReviewState.CHANGES_REQUESTED, ReviewState.fromPlatformState("changes_requested"))
        assertEquals(ReviewState.CHANGES_REQUESTED, ReviewState.fromPlatformState("request_changes"))
        assertEquals(ReviewState.CHANGES_REQUESTED, ReviewState.fromPlatformState("request-changes"))
        assertEquals(ReviewState.CHANGES_REQUESTED, ReviewState.fromPlatformState("CHANGES_REQUESTED"))
    }

    @Test
    fun `commented maps correctly`() {
        assertEquals(ReviewState.COMMENTED, ReviewState.fromPlatformState("commented"))
        assertEquals(ReviewState.COMMENTED, ReviewState.fromPlatformState("comment"))
        assertEquals(ReviewState.COMMENTED, ReviewState.fromPlatformState("COMMENTED"))
    }

    @Test
    fun `dismissed maps correctly`() {
        assertEquals(ReviewState.DISMISSED, ReviewState.fromPlatformState("dismissed"))
        assertEquals(ReviewState.DISMISSED, ReviewState.fromPlatformState("dismiss"))
        assertEquals(ReviewState.DISMISSED, ReviewState.fromPlatformState("DISMISSED"))
    }

    @Test
    fun `unknown state maps to PENDING`() {
        assertEquals(ReviewState.PENDING, ReviewState.fromPlatformState("pending"))
        assertEquals(ReviewState.PENDING, ReviewState.fromPlatformState("unknown"))
        assertEquals(ReviewState.PENDING, ReviewState.fromPlatformState(""))
        assertEquals(ReviewState.PENDING, ReviewState.fromPlatformState("some_other_state"))
    }

    @Test
    fun `default Approval state is APPROVED`() {
        val approval = Approval(user = User(username = "test", name = "Test"))
        assertEquals(ReviewState.APPROVED, approval.state)
    }

    @Test
    fun `default Approval method is CODE_REVIEW`() {
        val approval = Approval(user = User(username = "test", name = "Test"))
        assertEquals(ApprovalMethod.CODE_REVIEW, approval.method)
    }
}
