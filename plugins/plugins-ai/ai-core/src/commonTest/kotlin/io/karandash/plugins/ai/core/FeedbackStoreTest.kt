package io.karandash.plugins.ai.core

import io.karandash.api.finding.InlineFindingRef
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.utils.finding.AnchorFingerprint
import io.karandash.utils.finding.FindingFingerprint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FeedbackStoreTest {
    private fun finding(
        rule: String = "risk-scoring",
        message: String = "High risk change",
        filePath: String? = "src/main.kt",
    ): Finding =
        Finding(
            severity = Severity.WARNING,
            message = message,
            rule = rule,
            location = filePath?.let { Location.FileRef(it) },
        )

    @Test
    fun `dismiss and isDismissed returns true`() {
        val store = FeedbackStore()
        store.dismiss("risk-scoring", "src/main.kt", "High risk change".hashCode())
        assertTrue(store.isDismissed("risk-scoring", "src/main.kt", "High risk change".hashCode()))
    }

    @Test
    fun `non-dismissed finding returns false`() {
        val store = FeedbackStore()
        assertFalse(store.isDismissed("risk-scoring", "src/main.kt", "High risk change".hashCode()))
    }

    @Test
    fun `filterFindings separates kept vs filtered`() {
        val store = FeedbackStore()
        val dismissedFinding = finding(message = "Known false positive")
        val keptFinding = finding(message = "Real issue")

        store.dismiss(dismissedFinding.rule, "src/main.kt", dismissedFinding.message.hashCode())

        val result = store.filterFindings(listOf(dismissedFinding, keptFinding))

        assertEquals(1, result.kept.size)
        assertEquals(keptFinding, result.kept[0])
        assertEquals(1, result.filtered.size)
        assertEquals(dismissedFinding, result.filtered[0])
        assertEquals(1, result.filteredCount)
    }

    @Test
    fun `dismiss count increments on repeated dismissals`() {
        val store = FeedbackStore()
        store.dismiss("risk-scoring", "src/main.kt", 42)
        store.dismiss("risk-scoring", "src/main.kt", 42)
        store.dismiss("risk-scoring", "src/main.kt", 42)

        assertEquals(1, store.dismissCount())
        assertTrue(store.isDismissed("risk-scoring", "src/main.kt", 42))
    }

    @Test
    fun `LRU eviction when maxEntries exceeded`() {
        val store = FeedbackStore(maxEntries = 2)
        store.dismiss("rule", "file1.kt", 1)
        store.dismiss("rule", "file2.kt", 2)
        // Adding a third entry should evict the first (oldest)
        store.dismiss("rule", "file3.kt", 3)

        assertEquals(2, store.dismissCount())
        assertFalse(store.isDismissed("rule", "file1.kt", 1))
        assertTrue(store.isDismissed("rule", "file2.kt", 2))
        assertTrue(store.isDismissed("rule", "file3.kt", 3))
    }

    @Test
    fun `clear empties the store`() {
        val store = FeedbackStore()
        store.dismiss("risk-scoring", "src/main.kt", 42)
        store.dismiss("review-comments", null, 99)

        assertEquals(2, store.dismissCount())
        store.clear()
        assertEquals(0, store.dismissCount())
        assertFalse(store.isDismissed("risk-scoring", "src/main.kt", 42))
    }

    @Test
    fun `toSerializable and fromEntries roundtrip preserves entries`() {
        val store = FeedbackStore()
        store.dismiss("risk-scoring", "src/main.kt", 42)
        store.dismiss("review-comments", null, 99)
        store.dismiss("risk-scoring", "src/main.kt", 42) // bump dismiss count

        val restored = FeedbackStore.fromEntries(store.toSerializable())

        assertEquals(store.dismissCount(), restored.dismissCount())
        assertTrue(restored.isDismissed("risk-scoring", "src/main.kt", 42))
        assertTrue(restored.isDismissed("review-comments", null, 99))
        assertFalse(restored.isDismissed("risk-scoring", "other.kt", 42))
    }

    @Test
    fun `fingerprint is stable for same inputs`() {
        val fp1 = FeedbackStore.fingerprint("risk-scoring", "src/main.kt", 42)
        val fp2 = FeedbackStore.fingerprint("risk-scoring", "src/main.kt", 42)
        assertEquals(fp1, fp2)
    }

    @Test
    fun `different file paths produce different fingerprints`() {
        val fp1 = FeedbackStore.fingerprint("rule", "src/a.kt", 42)
        val fp2 = FeedbackStore.fingerprint("rule", "src/b.kt", 42)
        assertTrue(fp1 != fp2)
    }

    @Test
    fun `null file path handled correctly`() {
        val store = FeedbackStore()
        store.dismiss("risk-scoring", null, 42)

        assertTrue(store.isDismissed("risk-scoring", null, 42))
        assertFalse(store.isDismissed("risk-scoring", "src/main.kt", 42))
    }

    @Test
    fun `filterFindings with null location finding`() {
        val store = FeedbackStore()
        val noLocFinding = Finding(
            severity = Severity.WARNING,
            message = "Global issue",
            rule = "risk-scoring",
            location = null,
        )
        store.dismiss("risk-scoring", null, "Global issue".hashCode())

        val result = store.filterFindings(listOf(noLocFinding))
        assertEquals(0, result.kept.size)
        assertEquals(1, result.filteredCount)
    }

    @Test
    fun `fromEntries with no entries returns empty store`() {
        val store = FeedbackStore.fromEntries(emptyList())
        assertEquals(0, store.dismissCount())
    }

    @Test
    fun `semantic fingerprint filters finding with different numbers`() {
        val store = FeedbackStore()
        val msg1 = "Found 5 issues in this file"
        val location = Location.FileRef("src/main.kt")
        val semFp = FindingFingerprint.generate("ai-review", location, msg1)
        store.dismiss(
            rule = "ai-review",
            filePath = "src/main.kt",
            messageHash = msg1.hashCode(),
            semanticFingerprint = semFp,
        )

        val msg2 = "Found 3 issues in this file"
        val similarFinding = Finding(
            severity = Severity.WARNING,
            message = msg2,
            rule = "ai-review",
            location = location,
        )

        val result = store.filterFindings(listOf(similarFinding))
        assertEquals(0, result.kept.size)
        assertEquals(1, result.filteredCount)
    }

    @Test
    fun `semantic fingerprint does not match different rules`() {
        val store = FeedbackStore()
        val msg = "Found 5 issues in this file"
        val location = Location.FileRef("src/main.kt")
        val semFp = FindingFingerprint.generate("ai-review", location, msg)
        store.dismiss(
            rule = "ai-review",
            filePath = "src/main.kt",
            messageHash = msg.hashCode(),
            semanticFingerprint = semFp,
        )

        val differentRuleFinding = Finding(
            severity = Severity.WARNING,
            message = "Found 3 issues in this file",
            rule = "ai-security",
            location = location,
        )

        val result = store.filterFindings(listOf(differentRuleFinding))
        assertEquals(1, result.kept.size)
    }

    @Test
    fun `fromEntries preserves semantic index`() {
        val store = FeedbackStore()
        val msg = "Found 5 issues"
        val location = Location.FileRef("src/main.kt")
        val semFp = FindingFingerprint.generate("ai-review", location, msg)
        store.dismiss(
            rule = "ai-review",
            filePath = "src/main.kt",
            messageHash = msg.hashCode(),
            semanticFingerprint = semFp,
        )

        val entries = store.toSerializable()
        val restored = FeedbackStore.fromEntries(entries)

        val similarFinding = Finding(
            severity = Severity.WARNING,
            message = "Found 3 issues",
            rule = "ai-review",
            location = location,
        )
        val result = restored.filterFindings(listOf(similarFinding))
        assertEquals(0, result.kept.size)
        assertEquals(1, result.filteredCount)
    }

    @Test
    fun `entries without semantic fingerprint match the exact message`() {
        val store = FeedbackStore()
        store.dismiss("ai-review", "src/main.kt", "exact message".hashCode())

        val finding = finding(rule = "ai-review", message = "exact message", filePath = "src/main.kt")
        val result = store.filterFindings(listOf(finding))
        assertEquals(0, result.kept.size)
        assertEquals(1, result.filteredCount)
    }

    @Test
    fun `fingerprint no collision when rule contains pipe`() {
        val fp1 = FeedbackStore.fingerprint("a|b", "c", 0)
        val fp2 = FeedbackStore.fingerprint("a", "b|c", 0)
        assertNotEquals(fp1, fp2)
    }

    @Test
    fun `LRU eviction cleans up semantic index`() {
        val store = FeedbackStore(maxEntries = 2)
        val loc1 = Location.FileRef("a.kt")
        val loc2 = Location.FileRef("b.kt")
        val loc3 = Location.FileRef("c.kt")
        val sem1 = FindingFingerprint.generate("r", loc1, "msg N")
        val sem2 = FindingFingerprint.generate("r", loc2, "msg N")
        val sem3 = FindingFingerprint.generate("r", loc3, "msg N")

        store.dismiss("r", "a.kt", 1, semanticFingerprint = sem1)
        store.dismiss("r", "b.kt", 2, semanticFingerprint = sem2)
        store.dismiss("r", "c.kt", 3, semanticFingerprint = sem3)

        assertEquals(2, store.dismissCount())
        val findingA = Finding(
            severity = Severity.WARNING,
            message = "msg 1",
            rule = "r",
            location = loc1,
        )
        val resultA = store.filterFindings(listOf(findingA))
        assertEquals(1, resultA.kept.size, "evicted entry should not filter")
    }

    // Reply verdicts keyed by anchor fingerprint

    private fun anchored(
        message: String,
        fpv: String,
    ) = finding(message = message).copy(metadata = mapOf(WellKnownMetadataKeys.ANCHOR_FINGERPRINT to fpv))

    private fun ref(fpv: String) =
        InlineFindingRef(rule = "risk-scoring", path = "src/main.kt", messageHash = 1, messagePrefix = "Old text", fpv = fpv)

    @Test
    fun `rejecting reply drops a paraphrase on the same anchor while another anchor survives`() {
        val store = FeedbackStore()

        val verdict = store.recordReply(ref("fpv-a"), "Это ложное срабатывание")
        val result = store.filterFindings(listOf(anchored("Reworded concern", "fpv-a"), anchored("Reworded concern", "fpv-b")))

        assertEquals(ReplyVerdict.REJECTED, verdict)
        assertEquals(listOf("fpv-a"), result.filtered.map { it.metadata[WellKnownMetadataKeys.ANCHOR_FINGERPRINT] })
        assertEquals(listOf("fpv-b"), result.kept.map { it.metadata[WellKnownMetadataKeys.ANCHOR_FINGERPRINT] })
        assertEquals("Old text", store.dismissedForRule("risk-scoring").single().messagePrefix)
    }

    @Test
    fun `accepting reply is recorded as accepted and filters nothing`() {
        val store = FeedbackStore()

        val verdict = store.recordReply(ref("fpv-a"), "Fixed, thanks")

        assertEquals(ReplyVerdict.ACCEPTED, verdict)
        assertTrue(store.isAccepted("fpv-a"))
        assertFalse(store.isAccepted("fpv-b"))
        assertEquals(0, store.dismissCount())
        assertEquals(1, store.filterFindings(listOf(anchored("Reworded concern", "fpv-a"))).kept.size)
    }

    @Test
    fun `unclear reply records nothing`() {
        val store = FeedbackStore()

        assertEquals(ReplyVerdict.UNKNOWN, store.recordReply(ref("fpv-a"), "why is this a problem?"))
        assertEquals(0, store.dismissCount())
        assertFalse(store.isAccepted("fpv-a"))
    }

    @Test
    fun `rejection of a marker without fpv drops the exact message`() {
        val store = FeedbackStore()
        store.recordReply(ref(""), "false positive")

        val result = store.filterFindings(listOf(finding(message = "x"), anchored("Reworded", "")))

        assertEquals(2, result.kept.size)
        assertTrue(store.isDismissed("risk-scoring", "src/main.kt", 1))
    }

    @Test
    fun `verdicts survive a serialization roundtrip`() {
        val store = FeedbackStore()
        store.recordReply(ref("fpv-a"), "false positive")
        store.recordReply(ref("fpv-b"), "исправил")

        val restored = FeedbackStore.fromEntries(store.toSerializable(), store.acceptedAnchors())

        assertEquals(1, restored.filterFindings(listOf(anchored("Reworded", "fpv-a"))).filteredCount)
        assertTrue(restored.isAccepted("fpv-b"))
    }

    @Test
    fun `rejecting a finding on one of two identical lines keeps the finding on the other`() {
        val change = FileChange(
            path = "src/main.kt",
            changeType = ChangeType.MODIFIED,
            diffContent = "@@ -1,1 +1,4 @@\n ctx\n+    return null\n+val y = 2\n+    return null",
        )
        val first = Finding(Severity.WARNING, "Null leaks here", "risk-scoring", Location.FileRef("src/main.kt", 2))
        val second = Finding(Severity.WARNING, "Null also leaks", "risk-scoring", Location.FileRef("src/main.kt", 4))
        val (a, b) = AnchorFingerprint.stamp(listOf(first, second), listOf(change))
        val store = FeedbackStore()
        val fpvA = a.metadata.getValue(WellKnownMetadataKeys.ANCHOR_FINGERPRINT)
        store.recordReply(ref(fpvA).copy(messageHash = a.message.hashCode()), "false positive")

        val result = store.filterFindings(listOf(a, b))

        assertEquals(listOf(a), result.filtered)
        assertEquals(listOf(b), result.kept)
    }
}
