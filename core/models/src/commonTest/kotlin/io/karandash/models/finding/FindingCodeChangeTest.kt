package io.karandash.models.finding

import io.karandash.models.support.WellKnownMetadataKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FindingCodeChangeTest {
    private fun finding(
        location: Location?,
        existing: String? = null,
        suggested: String? = "val x = 2",
    ) = Finding(severity = Severity.WARNING, message = "use val", rule = "ai-code-suggestions").copy(
        location = location,
        metadata = listOfNotNull(
            existing?.let { WellKnownMetadataKeys.EXISTING_CODE to it },
            suggested?.let { WellKnownMetadataKeys.SUGGESTED_CODE to it },
        ).toMap(),
    )

    @Test
    fun `a file line with suggested code is a one-line change`() {
        val change = finding(Location.FileRef("a.kt", 7)).codeChange()

        assertEquals(CodeChange("a.kt", 7, 7, "val x = 2"), change)
    }

    @Test
    fun `the location end line wins over the existing code span`() {
        val change = finding(Location.FileRef("a.kt", 3, 9), existing = "var x = 1\nx++").codeChange()

        assertEquals(9, change?.endLine)
        assertEquals("var x = 1\nx++", change?.existingCode)
    }

    @Test
    fun `without an end line the existing code span sets it`() {
        val change = finding(Location.FileRef("a.kt", 3), existing = "var x = 1\nx++\n").codeChange()

        assertEquals(4, change?.endLine)
    }

    @Test
    fun `no change without a line or suggested code or a file`() {
        assertNull(finding(Location.FileRef("a.kt")).codeChange())
        assertNull(finding(Location.FileRef("a.kt", 1), suggested = " ").codeChange())
        assertNull(finding(Location.CommitRef("abc")).codeChange())
        assertNull(finding(null).codeChange())
    }
}
