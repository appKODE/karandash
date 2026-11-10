package io.karandash.plugins.ai.core.performance

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PgoProfileTest {
    @BeforeTest
    fun setUp() {
        PgoProfile.reset()
    }

    @Test
    fun `recording tracks path counts`() {
        PgoProfile.enableProfiling()
        PgoProfile.recordPath("validation.title")
        PgoProfile.recordPath("validation.title")
        PgoProfile.recordPath("validation.commits")

        val paths = PgoProfile.hotPaths()
        val titlePath = paths.find { it.pathId == "validation.title" }
        val commitsPath = paths.find { it.pathId == "validation.commits" }

        assertEquals(2L, titlePath?.executionCount)
        assertEquals(1L, commitsPath?.executionCount)
    }

    @Test
    fun `hotPaths returns sorted by execution count descending`() {
        PgoProfile.enableProfiling()
        PgoProfile.recordPath("path.a")
        PgoProfile.recordPath("path.b")
        PgoProfile.recordPath("path.b")
        PgoProfile.recordPath("path.b")
        PgoProfile.recordPath("path.c")
        PgoProfile.recordPath("path.c")

        val paths = PgoProfile.hotPaths()

        assertEquals("path.b", paths[0].pathId)
        assertEquals("path.c", paths[1].pathId)
        assertEquals("path.a", paths[2].pathId)
    }

    @Test
    fun `hotPaths respects topN limit`() {
        PgoProfile.enableProfiling()
        repeat(10) { i -> PgoProfile.recordPath("path.$i") }

        val paths = PgoProfile.hotPaths(topN = 3)

        assertEquals(3, paths.size)
    }

    @Test
    fun `reset clears all data and disables profiling`() {
        PgoProfile.enableProfiling()
        PgoProfile.recordPath("path.x")
        PgoProfile.reset()

        assertEquals(0L, PgoProfile.totalExecutions())
        assertTrue(PgoProfile.hotPaths().isEmpty())

        // Profiling should be disabled after reset
        PgoProfile.recordPath("path.y")
        assertEquals(0L, PgoProfile.totalExecutions())
    }

    @Test
    fun `disabled profiling does not record paths`() {
        // profiling is disabled after reset (setUp calls reset)
        PgoProfile.recordPath("path.ignored")

        assertEquals(0L, PgoProfile.totalExecutions())
        assertTrue(PgoProfile.hotPaths().isEmpty())
    }

    @Test
    fun `totalExecutions sums all path counts`() {
        PgoProfile.enableProfiling()
        PgoProfile.recordPath("a")
        PgoProfile.recordPath("a")
        PgoProfile.recordPath("b")
        PgoProfile.recordPath("c")
        PgoProfile.recordPath("c")
        PgoProfile.recordPath("c")

        assertEquals(6L, PgoProfile.totalExecutions())
    }

    @Test
    fun `formatReport contains execution totals`() {
        PgoProfile.enableProfiling()
        PgoProfile.recordPath("hot.path")
        PgoProfile.recordPath("hot.path")

        val report = PgoProfile.formatReport()

        assertTrue(report.contains("Total path executions: 2"))
        assertTrue(report.contains("hot.path"))
    }

    @Test
    fun `hotPaths returns empty list when no paths recorded`() {
        assertTrue(PgoProfile.hotPaths().isEmpty())
    }

    @Test
    fun `enableProfiling allows subsequent recording`() {
        PgoProfile.enableProfiling()
        PgoProfile.recordPath("enabled.path")

        assertFalse(PgoProfile.hotPaths().isEmpty())
    }
}
