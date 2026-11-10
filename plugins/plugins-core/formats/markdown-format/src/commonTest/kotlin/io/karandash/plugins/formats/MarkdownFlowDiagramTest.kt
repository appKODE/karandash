package io.karandash.plugins.formats

import io.karandash.api.finding.AnalyzerProvenance
import io.karandash.api.finding.EnricherProvenance
import io.karandash.api.finding.FilterProvenance
import io.karandash.api.finding.ProvenanceStatus
import io.karandash.api.finding.ReportMetadata
import io.karandash.api.finding.ReviewCoverage
import io.karandash.api.finding.ReviewProvenance
import io.karandash.api.finding.ValidationReport
import io.karandash.api.finding.summarize
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.sdk.PluginContext
import io.karandash.testfixtures.TestFiles
import io.karandash.translations.polish.PolishTranslationModule
import io.karandash.translations.russian.RussianTranslationModule
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T: the Mermaid flow diagram of a run and the inline why footer. */
class MarkdownFlowDiagramTest {
    @BeforeTest
    fun registerPolishPack() {
        PolishTranslationModule.register()
        RussianTranslationModule.register()
    }

    private val provenance = ReviewProvenance(
        coverage = ReviewCoverage(totalFiles = 3, reviewedFiles = 2, chunksSkipped = 1),
        enrichers = listOf(
            EnricherProvenance("jira", ProvenanceStatus.SKIPPED, reason = "JIRA_TOKEN not set"),
            EnricherProvenance("ci", ProvenanceStatus.OK, tokens = 120),
        ),
        analyzers = listOf(
            AnalyzerProvenance("aiReview", model = "gpt-4o", inputTokens = 900, outputTokens = 80, findings = 3),
            AnalyzerProvenance("aiSecurity", status = ProvenanceStatus.FAILED, reason = "timeout [60s]"),
        ),
        filters = listOf(FilterProvenance("dedup", removed = 1), FilterProvenance(FilterProvenance.FEEDBACK, removed = 1)),
    )

    private val nodeLine = Regex("""^ {2}n\d+\["[^"\[\]<>{}|]*"]:::(ok|skip|fail)$""")
    private val edgeLine = Regex("""^ {2}n\d+ -->(\|\d+\|)? n\d+$""")
    private val classLine = Regex("""^ {2}classDef (ok|skip|fail) .+$""")

    @Test
    fun `flow diagram is a structurally valid mermaid flowchart with counts on the edges`() {
        val diagram = flowDiagram(provenance)
        val lines = diagram.trimEnd().lines()

        assertEquals("```mermaid", lines.first())
        assertEquals("```", lines.last())
        assertEquals("flowchart LR", lines[1])
        val body = lines.subList(2, lines.size - 1)
        val bad = body.filterNot { nodeLine.matches(it) || edgeLine.matches(it) || classLine.matches(it) }
        assertTrue(bad.isEmpty(), "invalid mermaid lines: $bad\n$diagram")
        val declared = body.mapNotNull { Regex("""^ {2}(n\d+)\[""").find(it)?.groupValues?.get(1) }.toSet()
        val referenced = body.flatMap { l -> Regex("""n\d+""").findAll(l.substringAfter("classDef", l)).map { it.value } }.toSet()
        assertEquals(declared, referenced, diagram)
        assertEquals(
            setOf("ok", "skip", "fail"),
            body.mapNotNull { Regex("""^ {2}classDef (\w+)""").find(it)?.groupValues?.get(1) }.toSet()
        )
        // 3 analyzer findings, dedup drops 1, feedback drops 1, 1 posted.
        listOf("-->|3|", "-->|2|", "-->|1|").forEach { label -> assertTrue(body.any { label in it }, "$label\n$diagram") }
        assertTrue(body.any { "aiSecurity" in it && it.endsWith(":::fail") }, diagram)
        assertTrue(body.any { "jira" in it && it.endsWith(":::skip") }, diagram)
    }

    @Test
    fun `flow diagram over the node cap falls back to a stage table`() {
        val many = provenance.copy(analyzers = (1..30).map { AnalyzerProvenance("rule$it", model = "m", findings = 1) })

        val out = flowDiagram(many)

        assertFalse("mermaid" in out, out)
        assertTrue("| Stage | Findings |" in out, out)
        assertTrue("| Analyzers | 30 |" in out, out)
        assertTrue("| Posted | 28 |" in out, out)
    }

    @Test
    fun `flow labels and the stage table follow the report language - ids and stage names stay as they are`() {
        val ru = SummaryLocale.of("ru")
        val many = provenance.copy(analyzers = (1..30).map { AnalyzerProvenance("rule$it", model = "m", findings = 1) })

        val diagram = provenanceBlock(provenance, MarkdownTransparencyConfig(flowDiagram = true), ru).substringAfter("```mermaid")
        val table = provenanceBlock(many, MarkdownTransparencyConfig(flowDiagram = true), ru).substringAfter("| Фильтр | Удалено |")

        assertTrue("Анализатор aiReview на gpt-4o" in diagram, diagram)
        assertTrue("Обогащение jira: JIRA_TOKEN not set" in diagram, diagram)
        assertTrue("Фильтр dedup: -1" in diagram, diagram)
        assertFalse("Analyzer " in diagram || "Posted" in diagram || "Context" in diagram, diagram)
        assertTrue("| Этап | Замечания |" in table, table)
        assertTrue("| Анализаторы | 30 |" in table, table)
        assertTrue("| dedup | 29 |" in table, table)
    }

    @Test
    fun `summary carries the diagram only when the flow diagram key is on`() {
        val off = provenanceBlock(provenance, MarkdownTransparencyConfig())
        val on = provenanceBlock(provenance, MarkdownTransparencyConfig(flowDiagram = true))

        assertFalse("```mermaid" in off)
        assertTrue("```mermaid" in on.substringBefore("</details>"), on)
    }

    private val report = ValidationReport(
        passed = true,
        findings = emptyList(),
        summary = emptyList<Finding>().summarize(),
        groupedByLocation = emptyMap(),
        inlineEligible = emptyList(),
        summaryOnly = emptyList(),
        complianceChecklist = emptyList(),
        explanations = emptyMap(),
        metadata = ReportMetadata(version = "test", timestamp = 0L),
        provenance = provenance,
    )

    private fun tempDir(): Path = Path(TestFiles.createTestTempDirectory("karandash-flow"), "flow")

    private fun read(path: Path): String = SystemFileSystem.source(path).buffered().use { it.readString() }

    private fun contextIn(dir: Path): PluginContext =
        object : PluginContext {
            override val pluginConfig: JsonElement = JsonObject(emptyMap())
            override val logger: Logger = NoOpLogger
            override val hostVersion: String = "test"
            override val workingDirectory: String = dir.toString()

            override fun env(name: String): String? = null
        }

    @Test
    fun `every run with provenance writes the flow diagram artifact even with the diagram key off`() =
        runTest {
            val dir = tempDir()

            val body = MarkdownFormatModule().formatTyped(report, MarkdownFormatConfig(flowArtifactDir = dir.toString())).body

            assertFalse("```mermaid" in body)
            assertEquals(flowDiagram(provenance), read(Path(dir, MarkdownFormatModule.FLOW_ARTIFACT_FILE)))
            assertEquals("*\n", read(Path(dir, ".gitignore")))
        }

    @Test
    fun `the flow diagram artifact follows the report language`() =
        runTest {
            val dir = tempDir()

            MarkdownFormatModule().formatTyped(report, MarkdownFormatConfig(language = "pl", flowArtifactDir = dir.toString()))

            assertEquals(flowDiagram(provenance, SummaryLocale.of("pl")), read(Path(dir, MarkdownFormatModule.FLOW_ARTIFACT_FILE)))
        }

    @Test
    fun `an existing artifact dir without a gitignore gets one`() =
        runTest {
            val dir = tempDir()
            SystemFileSystem.createDirectories(dir)

            MarkdownFormatModule().formatTyped(report, MarkdownFormatConfig(flowArtifactDir = dir.toString()))

            assertEquals("*\n", read(Path(dir, ".gitignore")))
        }

    @Test
    fun `an existing gitignore in the artifact dir is left as is`() =
        runTest {
            val dir = tempDir()
            SystemFileSystem.createDirectories(dir)
            SystemFileSystem.sink(Path(dir, ".gitignore")).buffered().use { it.writeString("prompts/\n") }

            MarkdownFormatModule().formatTyped(report, MarkdownFormatConfig(flowArtifactDir = dir.toString()))

            assertEquals("prompts/\n", read(Path(dir, ".gitignore")))
        }

    @Test
    fun `a relative artifact dir resolves against the context working directory`() =
        runTest {
            val workspace = tempDir()
            val module = MarkdownFormatModule().also { it.initialize(contextIn(workspace)) }

            module.formatTyped(report, MarkdownFormatConfig(flowArtifactDir = "debug"))

            assertEquals(flowDiagram(provenance), read(Path(workspace, "debug", MarkdownFormatModule.FLOW_ARTIFACT_FILE)))
        }

    @Test
    fun `a preview run writes no artifact`() =
        runTest {
            val dir = tempDir()

            MarkdownFormatModule().formatTyped(report, MarkdownFormatConfig(preview = true, flowArtifactDir = dir.toString()))

            assertFalse(SystemFileSystem.exists(dir))
        }

    @Test
    fun `a long node label is cut to a bounded length`() {
        val longId = "analyzer-" + "x".repeat(300)
        val p = provenance.copy(analyzers = listOf(AnalyzerProvenance(longId, findings = 1)))

        val labels = flowDiagram(p).lines().filter { nodeLine.matches(it) }.map { it.substringAfter("[\"").substringBefore("\"]") }

        assertTrue(labels.all { it.length <= FLOW_LABEL_MAX }, labels.joinToString("\n"))
        assertTrue(labels.any { it.startsWith("Analyzer analyzer-xxx") && it.endsWith("\u2026") }, labels.joinToString("\n"))
    }

    @Test
    fun `a node label cut at an emoji drops the whole emoji`() {
        val prefix = "Analyzer "
        val id = "a".repeat(FLOW_LABEL_MAX - 2 - prefix.length) + "\uD83D\uDE00" + "tail"
        val p = provenance.copy(analyzers = listOf(AnalyzerProvenance(id, findings = 1)))

        val diagram = flowDiagram(p)

        assertTrue(prefix + "a".repeat(FLOW_LABEL_MAX - 2 - prefix.length) + "\u2026\"]" in diagram, diagram)
        assertFalse('\uD83D' in diagram, diagram)
    }

    @Test
    fun `a short node label renders whole`() {
        val labels = flowDiagram(provenance).lines().filter { nodeLine.matches(it) }.map { it.substringAfter("[\"").substringBefore("\"]") }

        assertTrue(labels.any { "aiReview" in it && "\u2026" !in it }, labels.joinToString("\n"))
    }

    @Test
    fun `inline why footer appears only when enabled and the finding has a why`() {
        val finding = Finding(
            rule = "aiReview",
            severity = Severity.WARNING,
            message = "Null check missing",
            metadata = mapOf(WellKnownMetadataKeys.WHY to "aiReview on gpt-4o; context: jira"),
        )
        val plain = finding.copy(metadata = emptyMap())

        assertFalse("Why:" in formatInlineCommentBody(finding))
        val withWhy = formatInlineCommentBody(finding, inlineWhy = true)
        assertTrue("\n\nWhy: aiReview on gpt-4o; context: jira" in withWhy, withWhy)
        assertFalse("_Why" in withWhy || "jira_" in withWhy, withWhy)
        assertFalse("Why:" in formatInlineCommentBody(plain, inlineWhy = true))
    }
}
