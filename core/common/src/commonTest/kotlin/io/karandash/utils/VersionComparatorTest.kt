package io.karandash.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionComparatorTest {
    @Test
    fun `compare equal versions returns zero`() {
        assertEquals(0, VersionComparator.compare("1.2.3", "1.2.3"))
    }

    @Test
    fun `compare older version returns negative`() {
        assertTrue(VersionComparator.compare("1.0.0", "2.0.0") < 0)
    }

    @Test
    fun `compare newer version returns positive`() {
        assertTrue(VersionComparator.compare("2.0.0", "1.0.0") > 0)
    }

    @Test
    fun `compare handles missing patch component`() {
        assertEquals(0, VersionComparator.compare("1.2", "1.2.0"))
    }

    @Test
    fun `compare handles large and four-part versions and a non-numeric part as 0`() {
        assertTrue(VersionComparator.compare("10.20.30", "10.20.29") > 0)
        assertTrue(VersionComparator.compare("1.2.3.4", "1.2.3.3") > 0)
        assertEquals(0, VersionComparator.compare("1.2.beta", "1.2.0"))
    }

    @Test
    fun `compare strips v prefix`() {
        assertEquals(0, VersionComparator.compare("v1.2.3", "1.2.3"))
        assertEquals(0, VersionComparator.compare("V1.2.3", "1.2.3"))
    }

    @Test
    fun `compare strips range prefixes`() {
        assertEquals(0, VersionComparator.compare("^1.2.3", "1.2.3"))
        assertEquals(0, VersionComparator.compare("~1.2.3", "1.2.3"))
        assertEquals(0, VersionComparator.compare(">=1.2.3", "1.2.3"))
        assertEquals(0, VersionComparator.compare("!=1.2.3", "1.2.3"))
    }

    @Test
    fun `compare ignores build metadata and ranks a pre-release below its release`() {
        assertEquals(0, VersionComparator.compare("1.2.3+build", "1.2.3"))
        assertEquals(0, VersionComparator.compare("1.2.3-beta.1+build.42", "1.2.3-beta.1"))
        assertTrue(VersionComparator.compare("1.2.3-rc1", "1.2.3") < 0)
        assertTrue(VersionComparator.compare("1.2.4-rc1", "1.2.3") > 0)
    }

    @Test
    fun `isNewer follows semver pre-release precedence`() {
        assertTrue(VersionComparator.isNewer("1.0.0-rc1", "1.0.0"))
        assertFalse(VersionComparator.isNewer("1.0.0", "1.0.0-rc1"))
        assertTrue(VersionComparator.isNewer("1.0.0-rc1", "1.0.0-rc2"))
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0")
        assertEquals(ordered, ordered.reversed().sortedWith(VersionComparator::compare))
    }

    @Test
    fun `isNewer returns true for newer version`() {
        assertTrue(VersionComparator.isNewer("1.0.0", "2.0.0"))
    }

    @Test
    fun `isNewer returns false for same version`() {
        assertFalse(VersionComparator.isNewer("1.0.0", "1.0.0"))
    }

    @Test
    fun `isBelow returns true when current is below minimum`() {
        assertTrue(VersionComparator.isBelow("1.0.0", "2.0.0"))
        assertTrue(VersionComparator.isBelow("1.2.3", "1.2.4"))
        assertTrue(VersionComparator.isBelow("1.2", "1.3"))
    }

    @Test
    fun `isBelow returns false when current equals minimum`() {
        assertFalse(VersionComparator.isBelow("1.2.3", "1.2.3"))
    }

    @Test
    fun `isBelow returns false when current is above minimum`() {
        assertFalse(VersionComparator.isBelow("2.0.0", "1.0.0"))
    }

    @Test
    fun `isBelow strips range prefixes from current`() {
        assertTrue(VersionComparator.isBelow("^1.0.0", "2.0.0"))
        assertTrue(VersionComparator.isBelow("~1.0.0", "2.0.0"))
        assertTrue(VersionComparator.isBelow(">=1.0.0", "2.0.0"))
    }

    @Test
    fun `isBelow strips pre-release suffixes`() {
        assertTrue(VersionComparator.isBelow("1.0.0-rc1", "2.0.0"))
        assertFalse(VersionComparator.isBelow("2.0.0-rc1", "2.0.0"))
    }

    @Test
    fun `isBelow returns false for unparseable versions`() {
        assertFalse(VersionComparator.isBelow("latest", "1.0.0"))
        assertFalse(VersionComparator.isBelow("1.0.0", "latest"))
        assertFalse(VersionComparator.isBelow("*", "1.0.0"))
    }
}
