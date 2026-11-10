package io.karandash.utils.finding

import io.karandash.models.finding.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FindingFingerprintTest {
    @Test
    fun `same inputs produce same fingerprint`() {
        val fp1 = FindingFingerprint.generate("title-format", null, "Title too short")
        val fp2 = FindingFingerprint.generate("title-format", null, "Title too short")
        assertEquals(fp1, fp2)
    }

    @Test
    fun `different rules produce different fingerprints`() {
        val fp1 = FindingFingerprint.generate("title-format", null, "Title too short")
        val fp2 = FindingFingerprint.generate("branch-naming", null, "Title too short")
        assertNotEquals(fp1, fp2)
    }

    @Test
    fun `different locations produce different fingerprints`() {
        val loc1 = Location.FileRef("a.kt", 10)
        val loc2 = Location.FileRef("b.kt", 10)
        val fp1 = FindingFingerprint.generate("rule", loc1, "msg")
        val fp2 = FindingFingerprint.generate("rule", loc2, "msg")
        assertNotEquals(fp1, fp2)
    }

    @Test
    fun `numbers are normalized in message`() {
        val fp1 = FindingFingerprint.generate("rule", null, "Title has 5 characters")
        val fp2 = FindingFingerprint.generate("rule", null, "Title has 42 characters")
        assertEquals(fp1, fp2)
    }

    @Test
    fun `whitespace is normalized in message`() {
        val fp1 = FindingFingerprint.generate("rule", null, "Title   too   short")
        val fp2 = FindingFingerprint.generate("rule", null, "Title too short")
        assertEquals(fp1, fp2)
    }

    @Test
    fun `case is normalized in message`() {
        val fp1 = FindingFingerprint.generate("rule", null, "Title Too Short")
        val fp2 = FindingFingerprint.generate("rule", null, "title too short")
        assertEquals(fp1, fp2)
    }

    @Test
    fun `fingerprint is 16 characters hex`() {
        val fp = FindingFingerprint.generate("rule", null, "message")
        assertEquals(16, fp.length)
        // Verify all characters are hex digits
        for (ch in fp) {
            check(ch in '0'..'9' || ch in 'a'..'f') {
                "Expected hex character, got '$ch'"
            }
        }
    }

    @Test
    fun `null location produces consistent fingerprint`() {
        val fp1 = FindingFingerprint.generate("rule", null, "msg")
        val fp2 = FindingFingerprint.generate("rule", null, "msg")
        assertEquals(fp1, fp2)
    }

    @Test
    fun `commit ref location is included in fingerprint`() {
        val loc = Location.CommitRef("abc123")
        val fpWithLoc = FindingFingerprint.generate("rule", loc, "msg")
        val fpWithout = FindingFingerprint.generate("rule", null, "msg")
        assertNotEquals(fpWithLoc, fpWithout)
    }

    @Test
    fun `file ref line number affects fingerprint`() {
        val loc1 = Location.FileRef("a.kt", 10)
        val loc2 = Location.FileRef("a.kt", 20)
        val fp1 = FindingFingerprint.generate("rule", loc1, "msg")
        val fp2 = FindingFingerprint.generate("rule", loc2, "msg")
        assertNotEquals(fp1, fp2)
    }
}
