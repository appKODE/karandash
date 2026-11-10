package io.karandash.plugins.ai.core

import io.karandash.cache.cas.CasCache
import io.karandash.duration.KarandashDuration
import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

class AiStatePersistenceTest {
    private lateinit var tempDir: Path
    private lateinit var casCache: CasCache
    private lateinit var persistence: AiStatePersistence

    @BeforeTest
    fun setUp() {
        tempDir = TestFiles.createTestTempDirectory("karandash-ai-state-test")
        SystemFileSystem.createDirectories(tempDir)
        casCache = CasCache(baseDir = tempDir, namespace = "state")
        persistence = AiStatePersistence(casCache)
    }

    @AfterTest
    fun tearDown() {
        TestFiles.deleteRecursively(tempDir)
    }

    // -- Feedback --

    @Test
    fun saveFeedbackAndLoadFeedbackRoundtrip() {
        val store = FeedbackStore()
        store.dismiss("ai-review", "src/Foo.kt", "bad code".hashCode())

        persistence.saveFeedback("mr-42", store)

        val loaded = persistence.loadFeedback("mr-42")
        assertTrue(loaded.isDismissed("ai-review", "src/Foo.kt", "bad code".hashCode()))
        assertFalse(loaded.isDismissed("ai-review", "src/Bar.kt", "other".hashCode()))
    }

    @Test
    fun feedbackIsIsolatedPerMr() {
        val store = FeedbackStore()
        store.dismiss("ai-review", "src/Foo.kt", 42)

        persistence.saveFeedback("mr-1", store)

        val fromMr1 = persistence.loadFeedback("mr-1")
        val fromMr2 = persistence.loadFeedback("mr-2")
        assertTrue(fromMr1.isDismissed("ai-review", "src/Foo.kt", 42))
        assertFalse(fromMr2.isDismissed("ai-review", "src/Foo.kt", 42))
    }

    @Test
    fun loadFeedbackReturnsEmptyStoreOnMiss() {
        val store = persistence.loadFeedback("mr-nonexistent")
        assertEquals(0, store.dismissCount())
    }

    @Test
    fun loadFeedbackReturnsEmptyStoreOnCorruptBlob() {
        // Write garbage bytes directly into the CAS for an ai: key
        casCache.put("ai_mr-corrupt", "not valid json {{{".encodeToByteArray(), ttlMs = Long.MAX_VALUE / 2)

        val store = persistence.loadFeedback("mr-corrupt")
        assertEquals(0, store.dismissCount())
    }

    // -- Snapshot --

    @Test
    fun saveSnapshotAndLoadSnapshotRoundtrip() {
        val snapshot = mapOf("src/Foo.kt" to 12345L, "src/Bar.kt" to 67890L)

        persistence.saveSnapshot("mr-42-1", snapshot)

        val loaded = persistence.loadSnapshot("mr-42-1")
        assertEquals(snapshot, loaded)
    }

    @Test
    fun noCacheSkipsSnapshotButKeepsFeedback() {
        val store = FeedbackStore()
        store.dismiss("ai-review", "src/Foo.kt", 7)
        persistence.saveSnapshot("mr-5", mapOf("src/Foo.kt" to 1L))
        persistence.saveFeedback("mr-5", store)

        val noCache = AiStatePersistence(casCache, readCaches = false)

        assertTrue(noCache.loadSnapshot("mr-5").isEmpty())
        assertTrue(noCache.loadFeedback("mr-5").isDismissed("ai-review", "src/Foo.kt", 7))
    }

    @Test
    fun `no-cache writes no response cache`() {
        val entries = mapOf("k" to CachedResponse("reply", 1, 1, 2, "m", Clock.System.now().epochSeconds))
        AiStatePersistence(casCache, readCaches = false).saveResponseCache("mr-6", entries)

        assertTrue(persistence.loadResponseCache("mr-6").isEmpty())
    }

    @Test
    fun loadSnapshotReturnsEmptyMapOnMiss() {
        assertTrue(persistence.loadSnapshot("mr-99-1").isEmpty())
    }

    // -- Response cache --

    @Test
    fun saveResponseCacheAndLoadResponseCacheRoundtrip() {
        val now = Clock.System.now().epochSeconds
        val entries = mapOf(
            "key-abc123" to CachedResponse(
                content = "AI response text",
                promptTokens = 100,
                completionTokens = 50,
                totalTokens = 150,
                model = "gpt-4o-mini",
                createdAt = now,
            ),
        )

        persistence.saveResponseCache("mr-42", entries)

        val loaded = persistence.loadResponseCache("mr-42")
        assertEquals(1, loaded.size)
        val entry = loaded["key-abc123"]
        assertEquals("AI response text", entry?.content)
        assertEquals(100, entry?.promptTokens)
        assertEquals(50, entry?.completionTokens)
        assertEquals(150, entry?.totalTokens)
        assertEquals("gpt-4o-mini", entry?.model)
        assertEquals(now, entry?.createdAt)
    }

    @Test
    fun responseCacheIsIsolatedPerMr() {
        val now = Clock.System.now().epochSeconds
        val entries = mapOf("key-1" to CachedResponse("resp", 10, 10, 20, "gpt-4o-mini", now))

        persistence.saveResponseCache("mr-1", entries)

        assertEquals(1, persistence.loadResponseCache("mr-1").size)
        assertTrue(persistence.loadResponseCache("mr-2").isEmpty())
    }

    @Test
    fun loadResponseCachePreservesCreatedAt() {
        val oldTimestamp = Clock.System.now().epochSeconds - 7200L
        val entries = mapOf(
            "old-key" to CachedResponse(
                content = "old response",
                promptTokens = 10,
                completionTokens = 10,
                totalTokens = 20,
                model = "gpt-4o-mini",
                createdAt = oldTimestamp,
            ),
        )

        persistence.saveResponseCache("mr-old", entries)

        val loaded = persistence.loadResponseCache("mr-old")
        assertEquals(1, loaded.size)
        assertEquals(oldTimestamp, loaded["old-key"]?.createdAt)
    }

    // -- Findings cache --

    @Test
    fun saveFindingsCacheAndLoadFindingsCacheRoundtrip() {
        val findings = mapOf(
            "src/Foo.kt" to listOf(
                io.karandash.models.finding.Finding(
                    rule = "ai-analysis",
                    message = "Too complex",
                    severity = io.karandash.models.finding.Severity.WARNING,
                    location = io.karandash.models.finding.Location
                        .FileRef("src/Foo.kt"),
                ),
            ),
        )

        persistence.saveFindingsCache("mr-42", findings)

        val loaded = persistence.loadFindingsCache("mr-42")
        assertEquals(1, loaded.size)
        val fileFinding = loaded["src/Foo.kt"]?.firstOrNull()
        assertEquals("ai-analysis", fileFinding?.rule)
        assertEquals("Too complex", fileFinding?.message)
    }

    @Test
    fun findingsCacheIsIsolatedPerMr() {
        val findings = mapOf(
            "src/A.kt" to listOf(
                io.karandash.models.finding.Finding(
                    rule = "ai-analysis",
                    message = "issue",
                    severity = io.karandash.models.finding.Severity.WARNING,
                ),
            ),
        )

        persistence.saveFindingsCache("mr-1", findings)

        assertEquals(1, persistence.loadFindingsCache("mr-1").size)
        assertTrue(persistence.loadFindingsCache("mr-2").isEmpty())
    }

    // -- Lifecycle --

    @Test
    fun clearMrRemovesOnlyThatMrState() {
        persistence.saveSnapshot("mr-1", mapOf("a.kt" to 1L))
        persistence.saveSnapshot("mr-2", mapOf("b.kt" to 2L))

        persistence.clearMr("mr-1")

        assertTrue(persistence.loadSnapshot("mr-1").isEmpty())
        assertEquals(mapOf("b.kt" to 2L), persistence.loadSnapshot("mr-2"))
    }

    @Test
    fun clearAllRemovesAllState() {
        val store = FeedbackStore()
        store.dismiss("ai-review", "src/Foo.kt", 42)
        persistence.saveFeedback("mr-1", store)
        persistence.saveSnapshot("mr-1", mapOf("src/Foo.kt" to 1L))
        persistence.saveSnapshot("mr-2", mapOf("src/Bar.kt" to 2L))

        persistence.clearAll()

        assertEquals(0, persistence.loadFeedback("mr-1").dismissCount())
        assertTrue(persistence.loadSnapshot("mr-1").isEmpty())
        assertTrue(persistence.loadSnapshot("mr-2").isEmpty())
    }

    @Test
    fun listMrKeysReturnsAllStoredKeys() {
        persistence.saveSnapshot("mr-1", mapOf("a.kt" to 1L))
        persistence.saveSnapshot("mr-2", mapOf("b.kt" to 2L))
        persistence.saveSnapshot("mr-3", mapOf("c.kt" to 3L))

        val keys = persistence.listMrKeys().sorted()
        assertEquals(listOf("mr-1", "mr-2", "mr-3"), keys)
    }

    @Test
    fun listMrKeysReturnsEmptyWhenNothingStored() {
        assertTrue(persistence.listMrKeys().isEmpty())
    }

    @Test
    fun cleanupExpiredDoesNothingForNoExpiry() {
        val noExpiryPersistence = AiStatePersistence(casCache, ttl = KarandashDuration.NEVER)
        noExpiryPersistence.saveSnapshot("mr-1", mapOf("a.kt" to 1L))

        val removed = noExpiryPersistence.cleanupExpired()

        assertEquals(0, removed)
        assertEquals(mapOf("a.kt" to 1L), noExpiryPersistence.loadSnapshot("mr-1"))
    }

    @Test
    fun cleanupExpiredCountsAlreadyEvictedEntries() {
        // Write an already-expired entry directly into CAS (negative ttlMs → expiresAt in the past)
        casCache.put("ai_mr-expired", "{}".encodeToByteArray(), ttlMs = -1_000L)
        // Write a live entry via normal persistence
        persistence.saveSnapshot("mr-fresh", mapOf("a.kt" to 1L))

        val removed = persistence.cleanupExpired()

        // The expired entry returns null on get(), so cleanupExpired counts it
        assertEquals(1, removed)
        // Fresh entry is unaffected
        assertEquals(mapOf("a.kt" to 1L), persistence.loadSnapshot("mr-fresh"))
    }

    @Test
    fun mrKeyWithSpecialCharactersRoundtrips() {
        val snapshot = mapOf("src/Foo.kt" to 999L)

        persistence.saveSnapshot("org/repo:42@branch", snapshot)

        val loaded = persistence.loadSnapshot("org/repo:42@branch")
        assertEquals(snapshot, loaded)
    }

    // -- saveSnapshotAndFindings --

    @Test
    fun saveFindingsCacheReplacesAllKeys() {
        val f1 = io.karandash.models.finding.Finding(
            rule = "ai-analysis",
            message = "f1",
            severity = io.karandash.models.finding.Severity.WARNING,
            location = io.karandash.models.finding.Location
                .FileRef("src/A.kt"),
        )
        val f2 = io.karandash.models.finding.Finding(
            rule = "ai-analysis",
            message = "f2",
            severity = io.karandash.models.finding.Severity.WARNING,
            location = io.karandash.models.finding.Location
                .FileRef("src/B.kt"),
        )
        val f1New = io.karandash.models.finding.Finding(
            rule = "ai-analysis",
            message = "f1-new",
            severity = io.karandash.models.finding.Severity.ERROR,
            location = io.karandash.models.finding.Location
                .FileRef("src/A.kt"),
        )

        persistence.saveFindingsCache("mr-42", mapOf("src/A.kt" to listOf(f1), "src/B.kt" to listOf(f2)))
        persistence.saveFindingsCache("mr-42", mapOf("src/A.kt" to listOf(f1New)))

        val loaded = persistence.loadFindingsCache("mr-42")
        assertFalse(loaded.containsKey("src/B.kt"), "B should be gone after replacement")
        assertEquals(1, loaded["src/A.kt"]?.size)
        assertEquals("f1-new", loaded["src/A.kt"]?.firstOrNull()?.message)
    }

    @Test
    fun saveFindingsCacheWithEmptyMapClearsAll() {
        val f1 = io.karandash.models.finding.Finding(
            rule = "ai-analysis",
            message = "f1",
            severity = io.karandash.models.finding.Severity.WARNING,
            location = io.karandash.models.finding.Location
                .FileRef("src/A.kt"),
        )
        persistence.saveFindingsCache("mr-42", mapOf("src/A.kt" to listOf(f1)))

        persistence.saveFindingsCache("mr-42", emptyMap())

        val loaded = persistence.loadFindingsCache("mr-42")
        assertTrue(loaded.isEmpty())
    }

    @Test
    fun saveSnapshotDoesNotEvictFeedback() {
        val store = FeedbackStore()
        store.dismiss("ai-review", "src/Foo.kt", 42)
        persistence.saveFeedback("mr-42", store)

        persistence.saveSnapshot("mr-42", mapOf("src/Foo.kt" to 999L))

        val loadedFeedback = persistence.loadFeedback("mr-42")
        assertTrue(loadedFeedback.isDismissed("ai-review", "src/Foo.kt", 42))
    }

    @Test
    fun saveResponseCacheWithEmptyMapClearsOldEntries() {
        val now = Clock.System.now().epochSeconds
        val entries = mapOf("key-1" to CachedResponse("resp", 10, 10, 20, "gpt-4o-mini", now))
        persistence.saveResponseCache("mr-42", entries)
        assertEquals(1, persistence.loadResponseCache("mr-42").size)

        // Save empty map - should clear old entries (no early-return guard)
        persistence.saveResponseCache("mr-42", emptyMap())

        val loaded = persistence.loadResponseCache("mr-42")
        assertTrue(loaded.isEmpty())
    }

    // -- Finding round-trip (all fields) --

    @Test
    fun findingRoundTripPreservesAllFields() {
        val finding = io.karandash.models.finding.Finding(
            rule = "ai-complexity",
            message = "Function is too complex",
            severity = io.karandash.models.finding.Severity.ERROR,
            location = io.karandash.models.finding.Location
                .FileRef("src/Foo.kt", line = 42, endLine = 55),
            explanation = "Cyclomatic complexity exceeds threshold",
            suggestion = "Extract helper methods",
            metadata = mapOf("complexity" to "15", "threshold" to "10"),
            fingerprint = "abc123def456",
            priority = 5,
            confidence = 0.95f,
            category = io.karandash.models.finding.FindingCategory.CORRECTNESS,
            fixSuggestions = listOf(
                io.karandash.models.finding.FixSuggestion
                    .SetTitle("Refactor: simplify function")
            ),
            attachments = listOf(
                io.karandash.models.finding
                    .Attachment("details.md", "# Details", "text/markdown")
            ),
            relatedFindings = listOf("finding-id-1"),
            errorCode = "CC001",
        )

        persistence.saveFindingsCache("mr-roundtrip", mapOf("src/Foo.kt" to listOf(finding)))
        val loaded = persistence.loadFindingsCache("mr-roundtrip")

        val roundTripped = loaded["src/Foo.kt"]?.firstOrNull()
        assertEquals(finding, roundTripped)
    }

    @Test
    fun findingWithCommitRefLocationRoundTrips() {
        val finding = io.karandash.models.finding.Finding(
            rule = "ai-review",
            message = "Suspicious commit",
            severity = io.karandash.models.finding.Severity.WARNING,
            location = io.karandash.models.finding.Location
                .CommitRef(sha = "deadbeef1234"),
        )

        persistence.saveFindingsCache("mr-commit", mapOf("__mr__" to listOf(finding)))
        val loaded = persistence.loadFindingsCache("mr-commit")

        val roundTripped = loaded["__mr__"]?.firstOrNull()
        assertEquals(finding, roundTripped)
    }

    // -- Private helpers --
}
