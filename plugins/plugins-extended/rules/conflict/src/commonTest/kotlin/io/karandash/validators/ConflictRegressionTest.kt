package io.karandash.validators

import io.karandash.sdk.config.BlockMode
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pin: blockOnConflicts=false is the only way to silence the rule; the key is kept for compatibility. */
class ConflictRegressionTest {
    @Test
    fun `blockOnConflicts false silences a conflicted MR`() {
        val mr = createTestMergeRequest().copy(hasConflicts = true)
        assertEquals(0, ConflictValidator(ConflictConfig(blockOnConflicts = BlockMode.OFF)).validate(mr).size)
        assertEquals(1, ConflictValidator(ConflictConfig(blockOnConflicts = BlockMode.FAIL)).validate(mr).size)
    }
}
