package io.karandash.plugins.ai.pipeline

import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.config.AiPipelineConfig
import io.karandash.plugins.ai.statistics.RunRecord
import io.karandash.testfixtures.createTestMergeRequest
import io.karandash.translations.russian.RussianTranslationModule
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiReportGeneratorSectionsTest {
    private val findings = listOf(
        Finding(Severity.ERROR, "Null dereference", "ai-security", location = Location.FileRef("src/A.kt", 2)),
        Finding(Severity.INFO, "Use trailing comma formatting", "style"),
    )

    private val mr = createTestMergeRequest(
        title = "Touch A",
        changes = listOf(
            FileChange(
                path = "src/A.kt",
                changeType = ChangeType.MODIFIED,
                additions = 4,
                deletions = 0,
                diffContent = "@@ -1,1 +1,5 @@\n+fun a(x: Int) {\n+    if (x > 0) {\n" +
                    "+        for (i in 0..x) { if (i % 2 == 0) println(i) }\n+    }\n+}",
            ),
        ),
    )

    private fun record(
        i: Int,
        byRule: Map<String, Int>,
    ) = RunRecord(
        timestamp = i.toLong(),
        mrId = "mr-$i",
        totalFindings = byRule.values.sum(),
        errorCount = byRule.values.sum(),
        warningCount = 0,
        infoCount = 0,
        findingsByRule = byRule,
        platform = "gitlab",
        author = mr.author.username,
    )

    private val history = (0 until 8).map { i ->
        record(i, mapOf("flaky" to i % 2, "drifty" to if (i < 4) 0 else 5))
    }

    private fun generate(
        enabled: Set<String>,
        records: List<RunRecord> = history,
        config: JsonObject? = null,
        findings: List<Finding> = this.findings,
    ) = AiReportGenerator(AiPipelineConfig(), NoOpLogger, records).generate(findings, mr, config, "mr-1", enabled)

    @Test
    fun `no enabled report yields nothing`() {
        assertTrue(generate(emptySet()).isEmpty())
    }

    @Test
    fun `core reports summarise heatmap and check run`() {
        val reports = generate(setOf("risk-heatmap", "check-run-summary"))

        assertEquals(setOf("risk-heatmap", "check-run-summary"), reports.keys)
        assertTrue(reports.getValue("check-run-summary").startsWith("Conclusion: "), reports.getValue("check-run-summary"))
    }

    @Test
    fun `statistics reports need recorded runs`() {
        val enabled = setOf("team-scorecard", "roi", "developer-growth")

        assertEquals(enabled, generate(enabled).keys)
        assertTrue(generate(enabled, records = emptyList()).isEmpty())
    }

    @Test
    fun `compliance and provenance reports need findings`() {
        val enabled = setOf("compliance-evidence", AiReportGenerator.PROVENANCE_CHAIN_REPORT)

        assertEquals(enabled, generate(enabled).keys)
        assertTrue("mr-1" in generate(enabled).getValue("compliance-evidence"))
        assertTrue(generate(enabled, findings = emptyList()).isEmpty())
    }

    @Test
    fun `annotated diff and custom findings render from the mr and template`() {
        val config = buildJsonObject { put("findingTemplate", "{{severity}} {{rule}} at {{file}}:{{line}}") }

        val reports = generate(setOf("annotated-diff-html", "custom-findings"), config = config)

        assertTrue("<" in reports.getValue("annotated-diff-html"))
        assertTrue("ai-security at src/A.kt:2" in reports.getValue("custom-findings"), reports.getValue("custom-findings"))
        assertFalse("custom-findings" in generate(setOf("custom-findings")))
    }

    @Test
    fun `trend reports detect flaky rules and policy drift`() {
        val enabled = setOf("cross-platform", "flaky-rules", "policy-drift", "rule-predictions")

        val reports = generate(enabled)

        assertTrue("cross-platform" in reports, reports.keys.toString())
        assertTrue("flaky" in reports.getValue("flaky-rules"), reports.getValue("flaky-rules"))
        assertTrue("drifty" in reports.getValue("policy-drift"), reports.getValue("policy-drift"))
        assertTrue(generate(enabled, records = history.take(1)).keys.isEmpty())
    }

    @Test
    fun `research reports cover complexity and usefulness`() {
        val reports = generate(setOf("complexity-delta", "usefulness-analysis", "pagerank-pillars", "refactoring-analysis"))

        assertTrue(reports.getValue("complexity-delta").startsWith("Cognitive complexity increased by"))
        assertEquals("1 of 2 finding(s) classified as not-useful (style/formatting)", reports.getValue("usefulness-analysis"))
    }

    @Test
    fun `research routing and provenance reports follow the response language`() {
        RussianTranslationModule.register()
        val config = buildJsonObject {
            put("responseLanguage", "ru")
            putJsonArray("notifications") {
                addJsonObject {
                    putJsonObject("match") {
                        put("rule", "security")
                        putJsonArray("severities") { add("error") }
                    }
                    putJsonArray("targets") {
                        addJsonObject {
                            put("channel", "slack")
                            put("target", "#sec")
                        }
                    }
                }
            }
        }
        val enabled = setOf(
            "complexity-delta",
            "usefulness-analysis",
            "pagerank-pillars",
            "notification-routing",
            AiReportGenerator.PROVENANCE_CHAIN_REPORT,
        )

        val reports = generate(enabled, config = config)

        assertTrue(reports.getValue("complexity-delta").startsWith("Когнитивная сложность выросла на"), reports.toString())
        assertEquals("Замечаний признано бесполезными (стиль/форматирование): 1 из 2", reports.getValue("usefulness-analysis"))
        assertEquals("slack #sec — замечаний: 1", reports.getValue("notification-routing"))
        val provenance = reports.getValue(AiReportGenerator.PROVENANCE_CHAIN_REPORT)
        assertTrue(provenance.startsWith("## Цепочка происхождения проверки\nХеш цепочки: "), provenance)
        listOf("rank=", "finding(s)", "Chain hash", "Findings:").forEach { word ->
            assertTrue(reports.values.none { word in it }, "$word in $reports")
        }
    }

    @Test
    fun `notification routing groups findings per channel`() {
        val config = buildJsonObject {
            putJsonArray("notifications") {
                addJsonObject {
                    putJsonObject("match") {
                        put("rule", "security")
                        putJsonArray("severities") { add("error") }
                    }
                    putJsonArray("targets") {
                        addJsonObject {
                            put("channel", "slack")
                            put("target", "#sec")
                        }
                    }
                }
            }
        }

        assertEquals(
            "slack #sec: 1 finding(s)",
            generate(setOf("notification-routing", "pgo-profile"), config = config)["notification-routing"]
        )
        assertFalse("notification-routing" in generate(setOf("notification-routing")))
    }

    @Test
    fun `notification routing names each target url next to its channel`() {
        val config = buildJsonObject {
            putJsonArray("notifications") {
                addJsonObject {
                    putJsonObject("match") {}
                    putJsonArray("targets") {
                        addJsonObject {
                            put("channel", "webhook")
                            put("url", "https://hooks.example.com/a")
                        }
                        addJsonObject { put("channel", "email") }
                    }
                }
            }
        }

        val report = generate(setOf("notification-routing"), config = config)["notification-routing"].orEmpty()
        assertTrue("webhook https://hooks.example.com/a: " in report, report)
        assertTrue("email: " in report, report)
    }
}
