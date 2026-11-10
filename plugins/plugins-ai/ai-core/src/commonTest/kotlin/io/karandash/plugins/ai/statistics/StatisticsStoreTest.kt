package io.karandash.plugins.ai.statistics

import io.karandash.testfixtures.TestFiles
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class StatisticsStoreTest {
    private fun tempDir(): String {
        val dir = TestFiles.createTestTempDirectory("karandash-test-stats")
        SystemFileSystem.createDirectories(dir)
        return dir.toString()
    }

    private fun makeRecord(
        mrId: String? = "mr-1",
        total: Int = 3
    ) = RunRecord(
        timestamp = Clock.System.now().epochSeconds,
        mrId = mrId,
        totalFindings = total,
        errorCount = 1,
        warningCount = 1,
        infoCount = 1,
        findingsByRule = mapOf("title-format" to 2, "ai-review" to 1),
    )

    @Test
    fun `loadAll returns empty list when file absent`() {
        val dir = tempDir()
        try {
            val store = StatisticsStore(dir)
            assertTrue(store.loadAll().isEmpty())
        } finally {
            TestFiles.deleteRecursively(Path(dir))
        }
    }

    @Test
    fun `record persists a run and loadAll returns it`() {
        val dir = tempDir()
        try {
            val store = StatisticsStore(dir)
            val rec = makeRecord()
            store.record(rec)
            val loaded = store.loadAll()
            assertEquals(1, loaded.size)
            assertEquals(rec.mrId, loaded[0].mrId)
            assertEquals(rec.totalFindings, loaded[0].totalFindings)
        } finally {
            TestFiles.deleteRecursively(Path(dir))
        }
    }

    @Test
    fun `multiple records accumulate in order`() {
        val dir = tempDir()
        try {
            val store = StatisticsStore(dir)
            store.record(makeRecord("mr-1", 1))
            store.record(makeRecord("mr-2", 2))
            store.record(makeRecord("mr-3", 3))
            val loaded = store.loadAll()
            assertEquals(3, loaded.size)
            assertEquals("mr-1", loaded[0].mrId)
            assertEquals("mr-3", loaded[2].mrId)
        } finally {
            TestFiles.deleteRecursively(Path(dir))
        }
    }

    @Test
    fun `record evicts oldest when maxHistory exceeded`() {
        val dir = tempDir()
        try {
            val store = StatisticsStore(dir, maxHistory = 3)
            store.record(makeRecord("mr-1"))
            store.record(makeRecord("mr-2"))
            store.record(makeRecord("mr-3"))
            store.record(makeRecord("mr-4"))
            val loaded = store.loadAll()
            assertEquals(3, loaded.size)
            assertEquals("mr-2", loaded[0].mrId)
            assertEquals("mr-4", loaded[2].mrId)
        } finally {
            TestFiles.deleteRecursively(Path(dir))
        }
    }

    @Test
    fun `clear removes stored statistics`() {
        val dir = tempDir()
        try {
            val store = StatisticsStore(dir)
            store.record(makeRecord())
            store.clear()
            assertTrue(store.loadAll().isEmpty())
        } finally {
            TestFiles.deleteRecursively(Path(dir))
        }
    }

    @Test
    fun `clear on non-existent dir does not throw`() {
        val dir = tempDir()
        TestFiles.deleteRecursively(Path(dir))
        val store = StatisticsStore(dir)
        store.clear() // should not throw
    }

    @Test
    fun `corrupt file returns empty list`() {
        val dir = tempDir()
        try {
            val store = StatisticsStore(dir)
            store.record(makeRecord())
            // Overwrite file with corrupt content
            val statsFile = Path(dir, StatisticsStore.STATS_FILE)
            SystemFileSystem.sink(statsFile).buffered().use { sink ->
                sink.write("not valid json".encodeToByteArray())
                sink.flush()
            }
            assertTrue(store.loadAll().isEmpty())
        } finally {
            TestFiles.deleteRecursively(Path(dir))
        }
    }

    @Test
    fun `record preserves findingsByRule map`() {
        val dir = tempDir()
        try {
            val store = StatisticsStore(dir)
            val rec = makeRecord().copy(findingsByRule = mapOf("rule-a" to 5, "rule-b" to 2))
            store.record(rec)
            val loaded = store.loadAll().first()
            assertEquals(5, loaded.findingsByRule["rule-a"])
            assertEquals(2, loaded.findingsByRule["rule-b"])
        } finally {
            TestFiles.deleteRecursively(Path(dir))
        }
    }

    @Test
    fun `resolveDir honours ai statistics dir and defaults to the documented dir`() {
        val ai = JsonObject(mapOf("statistics" to JsonObject(mapOf("dir" to JsonPrimitive("build/stats")))))
        assertEquals("build/stats", StatisticsStore.resolveDir(ai))
        assertEquals(StatisticsStore.DEFAULT_STATS_DIR, StatisticsStore.resolveDir(null))
        assertEquals(StatisticsStore.DEFAULT_STATS_DIR, StatisticsStore.resolveDir(JsonObject(emptyMap())))
    }

    /**
     * The stats file is replaced through a temp file and a rename, and an unreadable file is
     * moved aside instead of being overwritten by the next run, so a crashed write never wipes the history.
     */
    @Test
    fun `an unreadable stats file is kept aside and the write leaves no temp file`() {
        val dir = tempDir()
        try {
            val store = StatisticsStore(dir)
            val statsFile = Path(dir, StatisticsStore.STATS_FILE)
            SystemFileSystem.sink(statsFile).buffered().use { it.write("{\"runs\":[{\"trunc".encodeToByteArray()) }
            store.record(makeRecord())
            assertEquals(1, store.loadAll().size)
            val names = SystemFileSystem.list(Path(dir)).map { it.name }.sorted()
            assertEquals(listOf(StatisticsStore.STATS_FILE, StatisticsStore.STATS_FILE + ".corrupt"), names)
        } finally {
            TestFiles.deleteRecursively(Path(dir))
        }
    }
}
