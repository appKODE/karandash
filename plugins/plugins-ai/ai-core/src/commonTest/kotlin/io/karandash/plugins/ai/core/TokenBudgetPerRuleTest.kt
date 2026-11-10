package io.karandash.plugins.ai.core

import io.karandash.plugins.ai.statistics.RunRecord
import io.karandash.plugins.ai.statistics.StatisticsStore
import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class TokenBudgetPerRuleTest {
    private fun tempDir(): String {
        val base = SystemFileSystem.resolve(Path("."))
        val dir = Path(base, "test-budget-${Clock.System.now().toEpochMilliseconds()}")
        SystemFileSystem.createDirectories(dir)
        return dir.toString()
    }

    private fun makeRecord(findingsByRule: Map<String, Int>) =
        RunRecord(
            timestamp = Clock.System.now().epochSeconds,
            mrId = "mr-1",
            totalFindings = findingsByRule.values.sum(),
            errorCount = 0,
            warningCount = findingsByRule.values.sum(),
            infoCount = 0,
            findingsByRule = findingsByRule,
        )

    @Test
    fun `empty rules returns empty map`() {
        val result = TokenBudget.perRule(emptySet(), 10000, emptyMap())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `uniform allocation when no statistics store`() {
        val rules = setOf("rule-a", "rule-b", "rule-c", "rule-d")
        val result = TokenBudget.perRule(rules, 4000, emptyMap(), statisticsStore = null)

        assertEquals(4, result.size)
        result.values.forEach { allocated ->
            assertEquals(1000, allocated)
        }
    }

    @Test
    fun `all rules get some allocation`() {
        val rules = setOf("rule-a", "rule-b", "rule-c")
        val result = TokenBudget.perRule(rules, 9000, emptyMap(), statisticsStore = null)

        rules.forEach { rule ->
            assertNotNull(result[rule], "Rule $rule should have allocation")
            assertTrue((result[rule] ?: 0) > 0, "Rule $rule allocation should be positive")
        }
    }

    @Test
    fun `total allocation does not exceed budget`() {
        val dir = tempDir()
        try {
            val store = StatisticsStore(dir, maxHistory = 100)
            repeat(15) {
                store.record(makeRecord(mapOf("rule-a" to 1, "rule-b" to 0)))
            }
            val result = TokenBudget.perRule(
                setOf("rule-a", "rule-b"),
                5000,
                mapOf("rule-a" to 2000, "rule-b" to 1500),
                statisticsStore = store,
                minSamples = 10
            )

            val total = result.values.sum()
            assertTrue(total <= 5000 + 500, "Total $total should not greatly exceed budget 5000")
        } finally {
            TestFiles.deleteRecursively(Path(dir))
        }
    }

    @Test
    fun `higher hit rate rules get more budget`() {
        val dir = tempDir()
        try {
            val store = StatisticsStore(dir, maxHistory = 100)
            // rule-a fires every run, rule-b never fires
            repeat(15) {
                store.record(makeRecord(mapOf("rule-a" to 1, "rule-b" to 0)))
            }
            val costs = mapOf("rule-a" to 2000, "rule-b" to 2000)
            val result = TokenBudget.perRule(setOf("rule-a", "rule-b"), 10000, costs, statisticsStore = store, minSamples = 10)

            val aAlloc = result["rule-a"] ?: 0
            val bAlloc = result["rule-b"] ?: 0
            assertTrue(
                aAlloc >= bAlloc,
                "rule-a (hit rate 1.0) should get >= allocation than rule-b (hit rate 0.0), got a=$aAlloc b=$bAlloc"
            )
        } finally {
            TestFiles.deleteRecursively(Path(dir))
        }
    }

    @Test
    fun `minimum allocation enforced for rules not selected by knapsack`() {
        val dir = tempDir()
        try {
            val store = StatisticsStore(dir, maxHistory = 100)
            // rule-a has very high hit rate and cost consuming most budget; rule-b has zero hit rate
            repeat(15) {
                store.record(makeRecord(mapOf("rule-a" to 1, "rule-b" to 0)))
            }
            // Small budget so rule-b won't naturally get selected
            val result = TokenBudget.perRule(
                setOf("rule-a", "rule-b"),
                3000,
                mapOf("rule-a" to 3000, "rule-b" to 2000),
                statisticsStore = store,
                minSamples = 10
            )

            val bAlloc = result["rule-b"] ?: 0
            assertTrue(bAlloc >= 500, "rule-b should get at least minimum allocation 500, got $bAlloc")
        } finally {
            TestFiles.deleteRecursively(Path(dir))
        }
    }

    @Test
    fun `insufficient history falls back to uniform allocation`() {
        val dir = tempDir()
        try {
            val store = StatisticsStore(dir, maxHistory = 100)
            // Only 5 records, minSamples=10 → should fall back to uniform
            repeat(5) {
                store.record(makeRecord(mapOf("rule-a" to 1)))
            }
            val result = TokenBudget.perRule(setOf("rule-a", "rule-b"), 4000, emptyMap(), statisticsStore = store, minSamples = 10)

            assertEquals(2000, result["rule-a"])
            assertEquals(2000, result["rule-b"])
        } finally {
            TestFiles.deleteRecursively(Path(dir))
        }
    }
}
