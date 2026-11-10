package io.karandash.validators

import io.karandash.models.finding.mismatch
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DescriptionFormatFixHintTest {
    private val validator = DescriptionFormatValidator(
        DescriptionFormatConfig(listOf(SectionRule("Summary", "^## Summary", required = true))),
    )

    @Test
    fun `missing section states the problem and gives an example of the section`() {
        val finding = validator.validate(createTestMergeRequest(description = "text")).single()

        assertEquals("MR description is missing the required section Summary.", finding.message)
        assertEquals("Add the Summary section, e.g. `## Summary`.", finding.suggestion)
        assertNotNull(finding.metadata?.get(WellKnownMetadataKeys.EXPECTED_FORMAT))
        assertNull(finding.mismatch(), "a section is searched for, so there is no mismatch position")
    }

    @Test
    fun `empty description states the problem and gives an example of the first section`() {
        val finding = validator.validate(createTestMergeRequest(description = null)).single()

        assertEquals("MR description is empty; required sections are missing: Summary.", finding.message)
        assertEquals("Add the Summary section, e.g. `## Summary`.", finding.suggestion)
    }
}
