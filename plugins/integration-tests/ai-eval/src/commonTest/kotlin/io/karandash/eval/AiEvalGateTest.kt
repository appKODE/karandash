package io.karandash.eval

import io.karandash.models.support.KarandashJson
import io.karandash.platform.PlatformEnvironment
import io.karandash.testfixtures.GoldenFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes

/**
 * AI eval gate: replays the recorded responses of the labelled corpus through every analyzer and
 * fails when any analyzer's precision, recall or F1 drops below `eval/baseline.json`.
 * Writes `build/reports/ai-eval/report.json` and `summary.txt` on every run.
 */
class AiEvalGateTest {
    private val runner = EvalRunner()

    @Test
    fun `every analyzer is covered by the corpus`() {
        val cases = runner.loadCases()
        val scored = cases.flatMap { it.analyzers }.toSet()
        assertTrue(scored.containsAll(runner.analyzers.keys), "not covered: ${runner.analyzers.keys - scored}")
        val labelled = cases.flatMap { case -> case.expected.map { it.analyzer } }.toSet()
        assertTrue(labelled.containsAll(runner.analyzers.keys), "no positive label: ${runner.analyzers.keys - labelled}")
        assertTrue(cases.any { it.expected.isEmpty() }, "corpus needs at least one known-clean MR")
    }

    @Test
    fun `analyzers stay at or above the checked-in baseline`() =
        runTest(timeout = LIVE_TIMEOUT) {
            if (LiveResponder.flag(LiveResponder.LIVE_ENV)) {
                runLive()
                return@runTest
            }
            val pipeline = runner.replayPipeline().associate { it.caseId to it.counters.toMetrics() }
            val report = runner
                .run(runner.replay(), mode = "replay")
                .report
                .copy(pipeline = pipeline, notMeasured = NOT_MEASURED)
            writeReport(report)
            if (LiveResponder.flag(LiveResponder.UPDATE_BASELINE_ENV) ||
                PlatformEnvironment.getEnv(GoldenFiles.UPDATE_BASELINE_ENV) == "true"
            ) {
                EvalRunner.write(
                    BASELINE,
                    KarandashJson.Pretty.encodeToString(EvalReport.serializer(), report.copy(misses = emptyList())) + "\n"
                )
                return@runTest
            }
            val baseline = runner.loadBaseline()
            val regressions =
                regressions(baseline, report) + qualityRegressions(baseline, report) + pipelineRegressions(baseline.pipeline, pipeline)
            if (regressions.isNotEmpty()) {
                fail("AI eval regressed below baseline:\n" + regressions.joinToString("\n") + "\n\n" + summary(report))
            }
        }

    private suspend fun runLive() {
        val (responder, provider) = LiveResponder.create()
        try {
            val run = runner.run(responder, mode = "live")
            writeReport(run.report)
            if (LiveResponder.flag(LiveResponder.RECORD_ENV)) runner.writeRecordings(run.responses)
        } finally {
            provider.shutdown()
        }
    }

    private fun regressions(
        baseline: EvalReport,
        report: EvalReport,
    ): List<String> =
        (baseline.analyzers + (OVERALL to baseline.overall)).flatMap { (id, floor) ->
            val actual = if (id == OVERALL) report.overall else report.analyzers[id]
            if (actual == null) return@flatMap listOf("$id: missing from report")
            listOf(
                "precision" to (actual.precision to floor.precision),
                "recall" to (actual.recall to floor.recall),
                "f1" to (actual.f1 to floor.f1)
            ).filter { (_, values) -> values.first < values.second - EPSILON }
                .map { (metric, values) -> "$id $metric ${values.first} < baseline ${values.second}" }
        }

    @Test
    fun `pipeline gate flags note and token growth and silent drops`() {
        val base =
            mapOf("a" to PipelineMetrics(notes = 10, findingsKept = 10, findingsDropped = 0, silentDrops = 0, estimatedTokens = 1000))
        val within = base.getValue("a").copy(notes = 11, estimatedTokens = 1150)
        assertEquals(emptyList(), pipelineRegressions(base, mapOf("a" to within)))
        val worse = within.copy(notes = 12, estimatedTokens = 1151, silentDrops = 1)
        assertEquals(
            listOf("a: notes 12 > baseline 10 +10%", "a: estimated tokens 1151 > baseline 1000 +15%", "a: 1 silent drop(s)"),
            pipelineRegressions(base, mapOf("a" to worse)),
        )
        assertEquals(listOf("b: missing from baseline pipeline"), pipelineRegressions(base, base + ("b" to within)))
    }

    @Test
    fun `quality gate flags duplicate anchor and coverage regressions`() {
        val base = EvalMetrics(1.0, 1.0, 1.0, tp = 10, duplicates = 1, anchorable = 50, anchored = 50)
        val baseline = EvalReport(overall = base, analyzers = emptyMap(), coverage = EvalCoverage(cases = 5, analyzers = 15, labels = 25))
        val within = baseline.copy(overall = base.copy(anchored = 49), coverage = baseline.coverage.copy(cases = 6))
        assertEquals(emptyList(), qualityRegressions(baseline, within))
        val worse = baseline.copy(
            overall = base.copy(duplicates = 2, anchored = 48),
            coverage = EvalCoverage(cases = 4, analyzers = 14, labels = 24),
        )
        assertEquals(
            listOf(
                "overall duplicate rate 0.1667 > baseline 0.0909",
                "overall anchor accuracy 0.9600 < baseline 1.0000 -2pp",
                "coverage cases 4 < baseline 5",
                "coverage analyzers 14 < baseline 15",
                "coverage labels 24 < baseline 25",
            ),
            qualityRegressions(baseline, worse),
        )
    }

    /** Absolute floors hold independent of baseline.json, so regenerating the baseline cannot lower the bar. */
    @Test
    fun `absolute floors hold for the replayed corpus`() =
        runTest {
            val report = runner.run(runner.replay(), mode = "replay").report
            assertEquals(emptyList(), absoluteFloorViolations(report))
        }

    @Test
    fun `absolute floors flag weak reports`() {
        val weak = EvalReport(
            overall = EvalMetrics(0.5, 0.5, 0.5, tp = 1),
            analyzers = mapOf("a" to EvalMetrics(0.2, 0.2, 0.2, tp = 1)),
            coverage = EvalCoverage(cases = 9, analyzers = 15, labels = 25),
        )
        assertEquals(
            listOf(
                "overall precision 0.5000 < floor 0.8000",
                "overall recall 0.5000 < floor 0.8000",
                "a f1 0.2000 < floor 0.5000",
                "coverage cases 9 < floor 10",
            ),
            absoluteFloorViolations(weak),
        )
    }

    @Test
    fun `baseline records what the synthetic corpus cannot measure`() {
        assertTrue(
            runner
                .loadBaseline()
                .notMeasured.keys
                .containsAll(listOf("precisionLowerBound", "costUsd", "minedFixtures"))
        )
    }

    @Test
    fun `summary ratios render with four decimals on every target`() {
        assertEquals(listOf("0.0000", "0.6667", "1.0000", "0.0500"), listOf(0.0, 2.0 / 3, 1.0, 0.05).map { fixed4(it) })
    }

    private fun writeReport(report: EvalReport) {
        EvalRunner.write(Path(REPORT_DIR, "report.json"), KarandashJson.Pretty.encodeToString(EvalReport.serializer(), report) + "\n")
        EvalRunner.write(Path(REPORT_DIR, "summary.txt"), summary(report))
    }

    private companion object {
        const val OVERALL = "overall"
        const val EPSILON = 1e-9
        const val REPORT_DIR = "build/reports/ai-eval"
        val BASELINE = Path("src/commonTest/resources/eval/baseline.json")
        val LIVE_TIMEOUT = 30.minutes
        const val ANCHOR_DROP = 0.02

        /** Plan floors the committed synthetic corpus cannot measure; written into the baseline as such. */
        val NOT_MEASURED = mapOf(
            "precisionLowerBound" to
                "40 synthetic findings are too few for a meaningful interval; precision is a point estimate with zero tolerance",
            "costUsd" to "replay has no provider pricing; cost is estimated tokens (chars / 4) per case, +15% floor",
            "minedFixtures" to
                "mined MR fixtures are confidential client data and never committed; only the synthetic corpus runs in the gate",
        )

        /** Overall duplicate rate must not rise, anchor accuracy may drop 2pp, corpus coverage may not shrink. */
        fun qualityRegressions(
            baseline: EvalReport,
            report: EvalReport,
        ): List<String> {
            val base = baseline.overall
            val now = report.overall
            val cov = baseline.coverage
            val actual = report.coverage
            return listOfNotNull(
                "overall duplicate rate ${fixed4(now.duplicateRate)} > baseline ${fixed4(base.duplicateRate)}"
                    .takeIf { now.duplicateRate > base.duplicateRate + EPSILON },
                "overall anchor accuracy ${fixed4(now.anchorAccuracy)} < baseline ${fixed4(base.anchorAccuracy)} -2pp"
                    .takeIf { now.anchorAccuracy < base.anchorAccuracy - ANCHOR_DROP - EPSILON },
                "coverage cases ${actual.cases} < baseline ${cov.cases}".takeIf { actual.cases < cov.cases },
                "coverage analyzers ${actual.analyzers} < baseline ${cov.analyzers}".takeIf { actual.analyzers < cov.analyzers },
                "coverage labels ${actual.labels} < baseline ${cov.labels}".takeIf { actual.labels < cov.labels },
            )
        }

        const val MIN_OVERALL = 0.8
        const val MIN_ANALYZER_F1 = 0.5
        const val MIN_CASES = 10

        /** floors that do not move with `baseline.json`. */
        fun absoluteFloorViolations(report: EvalReport): List<String> =
            listOfNotNull(
                "overall precision ${fixed4(report.overall.precision)} < floor ${fixed4(MIN_OVERALL)}"
                    .takeIf { report.overall.precision < MIN_OVERALL },
                "overall recall ${fixed4(report.overall.recall)} < floor ${fixed4(MIN_OVERALL)}"
                    .takeIf { report.overall.recall < MIN_OVERALL },
            ) + report.analyzers.mapNotNull { (id, m) ->
                "$id f1 ${fixed4(m.f1)} < floor ${fixed4(MIN_ANALYZER_F1)}".takeIf { m.f1 < MIN_ANALYZER_F1 }
            } + listOfNotNull("coverage cases ${report.coverage.cases} < floor $MIN_CASES".takeIf { report.coverage.cases < MIN_CASES })

        const val NOTES_GROWTH = 1.10
        const val TOKENS_GROWTH = 1.15

        /** Full-pipeline gate per case: notes may grow 10%, estimated tokens 15%, silent drops never. */
        fun pipelineRegressions(
            baseline: Map<String, PipelineMetrics>,
            actual: Map<String, PipelineMetrics>,
        ): List<String> =
            actual.flatMap { (caseId, now) ->
                val base = baseline[caseId] ?: return@flatMap listOf("$caseId: missing from baseline pipeline")
                listOfNotNull(
                    "$caseId: notes ${now.notes} > baseline ${base.notes} +10%".takeIf { now.notes > base.notes * NOTES_GROWTH + EPSILON },
                    "$caseId: estimated tokens ${now.estimatedTokens} > baseline ${base.estimatedTokens} +15%"
                        .takeIf { now.estimatedTokens > base.estimatedTokens * TOKENS_GROWTH + EPSILON },
                    "$caseId: ${now.silentDrops} silent drop(s)".takeIf { now.silentDrops > 0 },
                )
            }

        fun summary(report: EvalReport): String =
            buildString {
                appendLine("AI eval (${report.mode})")
                appendLine("analyzer".padEnd(PAD) + "  P       R       F1      tp fp fn")
                (report.analyzers + (OVERALL to report.overall)).forEach { (id, m) ->
                    appendLine(
                        id.padEnd(PAD) + "  ${fixed4(m.precision)}  ${fixed4(m.recall)}  ${fixed4(m.f1)}  " +
                            listOf(m.tp, m.fp, m.fn).joinToString(" ") { it.toString().padStart(2) },
                    )
                }
                if (report.misses.isNotEmpty()) {
                    appendLine()
                    report.misses.forEach { appendLine(it) }
                }
            }

        const val PAD = 22
        private const val SCALE = 10_000

        /** `%.4f` for a non-negative ratio, without JVM-only `String.format`. */
        fun fixed4(value: Double): String {
            val scaled = (value * SCALE).roundToLong()
            return "${scaled / SCALE}.${(scaled % SCALE).toString().padStart(4, '0')}"
        }
    }
}
