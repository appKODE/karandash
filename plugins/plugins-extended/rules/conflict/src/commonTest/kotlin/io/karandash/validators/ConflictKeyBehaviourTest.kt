package io.karandash.validators

import io.karandash.sdk.config.BlockMode
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class ConflictKeyBehaviourTest {
    private val conflicted = createTestMergeRequest().copy(hasConflicts = true)

    @Test
    fun `blockOnConflicts reports a conflicting MR only when enabled`() {
        assertEquals(1, ConflictValidator(ConflictConfig(blockOnConflicts = BlockMode.FAIL)).validate(conflicted).size)
        assertEquals(emptyList(), ConflictValidator(ConflictConfig(blockOnConflicts = BlockMode.OFF)).validate(conflicted))
    }
}
