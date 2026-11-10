package io.karandash.api.finding

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.utils.finding.FindingFingerprint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InlineFindingMarkerTest {
    private val finding =
        Finding(Severity.WARNING, "Unused import -- remove it", "ai-code", Location.FileRef("src/A.kt", 3))

    @Test
    fun `marker round-trips the finding identity inside a comment body`() {
        val marker = InlineFindingMarker.of(finding)
        assertTrue(marker.startsWith(InlineFindingMarker.PREFIX))
        assertEquals(1, Regex("-->").findAll(marker).count(), "message must not close the HTML comment early")

        val body = "<!-- karandash-inline -->\n**WARNING** text\n$marker\n\n---\n\nother\n<!-- karandash-finding {broken -->"
        val ref = InlineFindingMarker.parse(body).single()

        assertEquals("ai-code", ref.rule)
        assertEquals("src/A.kt", ref.path)
        assertEquals(finding.message.hashCode(), ref.messageHash)
        assertEquals(FindingFingerprint.generate(finding.rule, finding.location, finding.message), ref.semanticFingerprint)
    }

    @Test
    fun `a rule or path holding the comment terminator cannot close the marker early`() {
        val hostile = finding.copy(rule = "r-->x", location = Location.FileRef("a-->b.kt", 3))
        val marker = InlineFindingMarker.of(hostile)

        assertEquals(1, Regex("-->").findAll(marker).count(), marker)
        val ref = InlineFindingMarker.parse(marker).single()
        assertEquals("r-->x", ref.rule)
        assertEquals("a-->b.kt", ref.path)
    }

    @Test
    fun `marker carries the anchor fingerprint`() {
        val anchored = finding.copy(metadata = mapOf(WellKnownMetadataKeys.ANCHOR_FINGERPRINT to "00ff00ff00ff00ff"))

        val ref = InlineFindingMarker.parse(InlineFindingMarker.of(anchored)).single()

        assertEquals("00ff00ff00ff00ff", ref.fpv)
    }

    @Test
    fun `marker without fpv parses`() {
        val unanchored =
            "<!-- karandash-finding {\"rule\":\"ai-code\",\"path\":\"src/A.kt\",\"hash\":1,\"fp\":\"abc\",\"msg\":\"Unused\"} -->"

        val ref = InlineFindingMarker.parse(unanchored).single()

        assertEquals("abc", ref.semanticFingerprint)
        assertEquals("", ref.fpv)
    }

    @Test
    fun `a bang comment close in the rule or path cannot end the marker early`() {
        val hostile = finding.copy(rule = "r--!>x", location = Location.FileRef("a--!>b.kt", 3))
        val marker = InlineFindingMarker.of(hostile)
        assertEquals(0, Regex("--!?>").findAll(marker.removeSuffix(" -->")).count(), marker)
        val ref = InlineFindingMarker.parse(marker).single()
        assertEquals("r--!>x", ref.rule)
        assertEquals("a--!>b.kt", ref.path)
    }
}
