package io.karandash.validators

import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class ContextTagFixHintTest {
    private val validator = ContextTagValidator(ContextTagConfig(allowed = listOf("ios", "core"), checkCommitTitles = false))

    @Test
    fun `missing tag states the problem and lists the allowed tags in the suggestion`() {
        val finding = validator.validate(createTestMergeRequest(title = "fix")).single()

        assertEquals("No context tag found in MR title.", finding.message)
        assertEquals("Add one of the allowed context tags to the MR title: `[ios]`, `[core]`.", finding.suggestion)
    }

    @Test
    fun `several tags state the problem and suggest keeping the first`() {
        val finding = validator.validate(createTestMergeRequest(title = "[ios][core] fix")).single()

        assertEquals("Multiple context tags found in MR title: [ios], [core].", finding.message)
        assertEquals("Keep only one context tag in the MR title, e.g. `[ios]`.", finding.suggestion)
    }
}
