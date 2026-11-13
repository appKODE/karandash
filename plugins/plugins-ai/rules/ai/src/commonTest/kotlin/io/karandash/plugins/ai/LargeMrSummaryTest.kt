package io.karandash.plugins.ai

import io.karandash.api.exception.ConfigParseException
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.config.AiLargeMrConfig
import io.karandash.plugins.ai.core.config.TestGapConfig
import io.karandash.plugins.ai.core.rules.AiExcludePolicy
import io.karandash.plugins.ai.dispatch.RuleCoverage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The summary names reviewed and not-reviewed review units, and units changed without tests. */
class LargeMrSummaryTest {
    private fun change(
        path: String,
        type: ChangeType = ChangeType.MODIFIED,
    ) = FileChange(path = path, changeType = type, additions = 1, diffContent = "@@ -1 +1 @@\n+x")

    private fun reviewedAll(changes: List<FileChange>) =
        listOf(RuleCoverage(files = changes.map { it.path }.toSet(), reviewed = changes.map { it.path }.toSet()))

    private fun gaps(
        changes: List<FileChange>,
        config: AiLargeMrConfig = AiLargeMrConfig(),
        excludePatterns: List<String> = AiExcludePolicy.DEFAULT_PATTERNS,
        level: String = TestGapConfig.MR,
    ) = LargeMrSummary
        .findings(changes, reviewedAll(changes), config, excludePatterns, TestGapConfig(level))
        .filter { it.rule == LargeMrSummary.TEST_GAP_RULE }

    @Test
    fun `test gaps collapse into one summary finding per MR`() {
        val twelve = gaps((1..22).map { change("m$it/A.kt") }).single()

        assertEquals("Test gap: 22 unit(s) change 22 production files and no test", twelve.message)
        assertTrue("- … and 2 more" in checkNotNull(twelve.explanation), twelve.explanation)
        assertEquals("Add or update tests for the listed units", twelve.suggestion)
        assertNull(twelve.location)
    }

    @Test
    fun `build scripts and type declarations are not production code`() {
        assertEquals(emptyList(), gaps(listOf(change("build.gradle.kts"), change("app/build.gradle.kts"), change("web/types/api.d.ts"))))
        assertEquals(listOf("web/types"), gaps(listOf(change("web/types/api.ts"))).map { it.metadata["unitNames"] })
    }

    @Test
    fun `test gaps follow the configured exclude patterns not the defaults`() {
        val changes = listOf(change("gen/Api.kt"), change("build/generated/Model.kt"))

        assertEquals(listOf("build/generated"), gaps(changes, excludePatterns = listOf("gen/**")).map { it.metadata["unitNames"] })
    }

    @Test
    fun `test-gaps false turns the test gap lines off`() {
        val changes = listOf(change("app/A.kt"))

        assertEquals(emptyList(), gaps(changes, AiLargeMrConfig(testGaps = false)))
        assertEquals(1, gaps(changes).size)
    }

    @Test
    fun `units with production changes and no test change get one test gap line`() {
        val changes = listOf(
            change("app/src/main/kotlin/A.kt"),
            change("app/src/main/kotlin/B.kt"),
            change("base/x.cc"),
            change("lib/src/main/kotlin/L.kt"),
            change("lib/src/test/kotlin/LTest.kt"),
            change("ui/src/main/kotlin/U.kt"),
            change("ui/src/androidTest/kotlin/UScreen.kt"),
            change("net/socket.cc"),
            change("net/socket_test.cc"),
            change("core/engine.cc"),
            change("core/tests/engine_check.cc"),
        )

        val found = gaps(changes, level = TestGapConfig.FILE)

        assertEquals(listOf("app,base"), found.map { it.metadata["unitNames"] })
        assertTrue(found.all { it.location == null && it.severity == Severity.INFO })
        assertTrue("2 unit(s) change 3 production files" in found.first().message, found.first().message)
    }

    @Test
    fun `at mr level a test changed in any unit means no test gap`() {
        val changes = listOf(change("app/src/main/kotlin/A.kt"), change("lib/src/main/kotlin/L.kt"), change("lib/src/test/kotlin/LTest.kt"))

        assertEquals(emptyList(), gaps(changes))
        assertEquals(listOf("app"), gaps(changes, level = TestGapConfig.FILE).map { it.metadata["unitNames"] })
        assertEquals(listOf(Severity.INFO), gaps(listOf(change("app/src/main/kotlin/A.kt"))).map { it.severity })
    }

    @Test
    fun `ai testGap level other than mr or file is a config error naming the key`() {
        val error = assertFailsWith<ConfigParseException> { TestGapConfig("unit").validated() }

        assertTrue("ai.testGap.level" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `generated lock vendor and deleted files never make a test gap`() {
        val changes = listOf(
            change("build/generated/Api.kt"),
            change("Cargo.lock"),
            change("vendor/lib/x.go"),
            change("web/node_modules/a/index.js"),
            change("docs/guide.md"),
            change("old/Gone.kt", ChangeType.DELETED),
        )

        assertEquals(emptyList(), gaps(changes))
    }

    private fun unitsLine(
        changes: List<FileChange>,
        coverage: RuleCoverage,
    ) = LargeMrSummary.findings(changes, listOf(coverage), AiLargeMrConfig(), AiExcludePolicy.DEFAULT_PATTERNS).filter {
        it.rule ==
            LargeMrSummary.RULE
    }

    @Test
    fun `the summary lists reviewed and not reviewed units when a budget skip lost a unit`() {
        val changes = listOf(change("api/A.kt"), change("web/W.ts"), change("vendor/lib/x.go"))
        val coverage = RuleCoverage(files = setOf("api/A.kt", "web/W.ts"), reviewed = setOf("api/A.kt"), chunks = 2, chunksSkipped = 1)

        val units = unitsLine(changes, coverage).single()

        // vendor/lib was excluded before review: neither reviewed nor not reviewed.
        assertEquals("Large MR: 3 review units: 1 reviewed, 1 not reviewed, 1 excluded or unchanged", units.message)
        val explanation = checkNotNull(units.explanation)
        assertTrue("Reviewed units:\n- api" in explanation, explanation)
        assertTrue("Not reviewed units:\n- web" in explanation && "vendor" !in explanation, explanation)
        assertNull(units.location)
    }

    @Test
    fun `code with a docs file the analyzer did not read gets no units line`() {
        val changes = listOf(change("api/A.kt"), change("docs/x.md"))

        assertEquals(emptyList(), unitsLine(changes, RuleCoverage(files = setOf("api/A.kt", "docs/x.md"), reviewed = setOf("api/A.kt"))))
    }

    @Test
    fun `code with an excluded lock file gets no units line`() {
        val changes = listOf(change("api/A.kt"), change("Cargo.lock"))
        val coverage =
            RuleCoverage(files = setOf("api/A.kt"), reviewed = setOf("api/A.kt"), excluded = setOf("Cargo.lock (matched **/*.lock)"))

        assertEquals(emptyList(), unitsLine(changes, coverage))
    }

    @Test
    fun `a unit dropped whole to fit the budget gets a units line`() {
        val changes = listOf(change("api/A.kt"), change("web/W.ts"))
        val coverage = RuleCoverage(files = setOf("api/A.kt", "web/W.ts"), reviewed = setOf("api/A.kt"), dropped = setOf("web/W.ts"))

        assertEquals(
            "Large MR: 2 review units: 1 reviewed, 1 not reviewed, 0 excluded or unchanged",
            unitsLine(changes, coverage).single().message
        )
    }

    @Test
    fun `an MR within max-units with every unit reviewed gets no units line`() {
        val changes = listOf(change("api/A.kt"), change("api/ATest.kt"))

        assertEquals(
            emptyList(),
            LargeMrSummary.findings(changes, reviewedAll(changes), AiLargeMrConfig(), AiExcludePolicy.DEFAULT_PATTERNS)
        )
    }

    @Test
    fun `an MR over max-units gets a split suggestion`() {
        val changes = listOf(change("a/A.kt"), change("b/B.kt"), change("c/C.kt")).flatMap {
            listOf(it, it.copy(path = it.path.replace(".kt", "Test.kt")))
        }

        val units = LargeMrSummary
            .findings(
                changes,
                reviewedAll(changes),
                AiLargeMrConfig(maxUnits = 2),
                AiExcludePolicy.DEFAULT_PATTERNS
            ).single()

        assertEquals("Large MR: 3 review units: 3 reviewed, 0 not reviewed, 0 excluded or unchanged", units.message)
        assertEquals("Consider splitting this MR: 3 review units is over ai.large-mr.max-units 2", units.suggestion)
        assertFalse("Consider splitting" in checkNotNull(units.explanation), "moved to suggestion, not copied")
    }

    @Test
    fun `nothing is emitted when no AI rule ran`() {
        assertEquals(
            emptyList(),
            LargeMrSummary.findings(listOf(change("app/src/main/A.kt")), emptyList(), AiLargeMrConfig(), AiExcludePolicy.DEFAULT_PATTERNS)
        )
    }
}
