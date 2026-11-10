package io.karandash.providers.common

import io.karandash.models.ChangeType
import io.karandash.models.CiJobStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdapterHelpersTest {
    @Test
    fun `a summary comment must open with the marker`() {
        assertTrue(AdapterHelpers.isSummaryComment("  \n<!-- m -->\nbody", "<!-- m -->"))
        assertFalse(AdapterHelpers.isSummaryComment("reply quoting <!-- m -->", "<!-- m -->"))
        assertFalse(AdapterHelpers.isSummaryComment(null, "<!-- m -->"))
    }

    @Test
    fun `change type covers every platform spelling and defaults to modified`() {
        val expected = mapOf(
            "added" to ChangeType.ADDED,
            "A" to ChangeType.ADDED,
            "modified" to ChangeType.MODIFIED,
            "edit" to ChangeType.MODIFIED,
            "changed" to ChangeType.MODIFIED,
            "M" to ChangeType.MODIFIED,
            "deleted" to ChangeType.DELETED,
            "removed" to ChangeType.DELETED,
            "D" to ChangeType.DELETED,
            "renamed" to ChangeType.RENAMED,
            "R" to ChangeType.RENAMED,
            "copied" to ChangeType.MODIFIED,
        )

        assertEquals(expected, expected.mapValues { (status, _) -> AdapterHelpers.mapChangeType(status) })
    }

    @Test
    fun `branch name drops only the refs heads prefix`() {
        assertEquals("feature/x", AdapterHelpers.extractBranchName("refs/heads/feature/x"))
        assertEquals("main", AdapterHelpers.extractBranchName("main"))
    }

    @Test
    fun `diff stats count added and removed lines but not file headers`() {
        val diff = "--- a/f\n+++ b/f\n@@ -1,2 +1,2 @@\n-old\n-older\n+new\n context"

        assertEquals(1 to 2, AdapterHelpers.parseDiffStats(diff))
        assertEquals(0 to 0, AdapterHelpers.parseDiffStats(""))
    }

    @Test
    fun `platform state is looked up case insensitively and unknown states are closed`() {
        val mappings = mapOf("open" to "opened", "merged" to "merged")

        assertEquals("opened", AdapterHelpers.mapPlatformState("OPEN", mappings))
        assertEquals("closed", AdapterHelpers.mapPlatformState("weird", mappings))
    }

    @Test
    fun `ci status is failed before pending before success and null without jobs`() {
        fun status(vararg states: String) = AdapterHelpers.aggregateCiStatus(states.map { CiJobStatus("j", it) })

        assertNull(status())
        assertEquals(AdapterHelpers.CI_FAILED, status("success", "Failure"))
        assertEquals(AdapterHelpers.CI_PENDING, status("success", "running"))
        assertEquals(AdapterHelpers.CI_SUCCESS, status("success", "skipped"))
    }
}
