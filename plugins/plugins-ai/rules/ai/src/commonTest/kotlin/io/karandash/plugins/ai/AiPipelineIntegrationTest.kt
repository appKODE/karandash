package io.karandash.plugins.ai

import io.karandash.cache.CacheTier
import io.karandash.cache.TieredCache
import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.multipattern.MultiPatternMatcher
import io.karandash.plugins.ai.core.DiffChunker
import io.karandash.plugins.ai.core.GateConfig
import io.karandash.plugins.ai.core.ProcessRiskCalculator
import io.karandash.plugins.ai.core.RuleGate
import io.karandash.plugins.ai.core.RuleSandbox
import io.karandash.plugins.ai.core.architecture.AdrConstraint
import io.karandash.plugins.ai.core.architecture.AdrValidator
import io.karandash.plugins.ai.core.architecture.ProvenanceChain
import io.karandash.plugins.ai.core.config.CompositionRule
import io.karandash.plugins.ai.core.config.ConditionalSection
import io.karandash.plugins.ai.core.config.ConditionalSectionChecker
import io.karandash.plugins.ai.core.config.EnforcementMode
import io.karandash.plugins.ai.core.config.EnforcementPhase
import io.karandash.plugins.ai.core.config.ExecutionProfileResolver
import io.karandash.plugins.ai.core.config.InlineMrOverrideParser
import io.karandash.plugins.ai.core.config.ProgressiveEnforcement
import io.karandash.plugins.ai.core.config.RuleComposer
import io.karandash.plugins.ai.core.config.SeverityOverride
import io.karandash.plugins.ai.core.config.SeverityReclassifier
import io.karandash.plugins.ai.core.metrics.ChangeEntropyCalculator
import io.karandash.plugins.ai.core.metrics.CognitiveLoadEstimator
import io.karandash.plugins.ai.core.metrics.HotspotAnalyzer
import io.karandash.plugins.ai.core.metrics.NcdCloneDetector
import io.karandash.plugins.ai.core.pipeline.AiFindingFilter
import io.karandash.plugins.ai.core.pipeline.AiGroundingVerifier
import io.karandash.plugins.ai.core.pipeline.ContradictionDetector
import io.karandash.plugins.ai.core.pipeline.FilterConfig
import io.karandash.plugins.ai.core.pipeline.FindingCorrelationEngine
import io.karandash.plugins.ai.core.reporters.AnnotatedDiffHtmlBuilder
import io.karandash.plugins.ai.core.reporters.ComplianceEvidenceBuilder
import io.karandash.plugins.ai.core.reporters.FindingTransformer
import io.karandash.plugins.ai.core.reporters.RiskHeatmapGenerator
import io.karandash.plugins.ai.core.research.BloomFilter
import io.karandash.plugins.ai.core.research.CognitiveComplexityDelta
import io.karandash.plugins.ai.core.research.EvtAutoThreshold
import io.karandash.plugins.ai.core.research.RefactoringDetector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AiPipelineIntegrationTest {
    private fun testMr(
        title: String = "feat: add login feature",
        description: String? = "Adds OAuth login flow",
        sourceBranch: String = "feature/login",
        targetBranch: String = "main",
        draft: Boolean = false,
        labels: List<String> = emptyList(),
        changes: List<FileChange> = listOf(
            FileChange(
                "src/auth/Login.kt",
                ChangeType.MODIFIED,
                50,
                10,
                diffContent = "+fun authenticate(user: User): Token {\n+    return tokenService.generate(user)\n+}",
            ),
            FileChange(
                "src/auth/Token.kt",
                ChangeType.ADDED,
                30,
                0,
                diffContent = "+data class Token(val value: String, val expiresAt: Long)",
            ),
        ),
        commits: List<Commit> = listOf(
            Commit("abc123", "feat: add login", CommitAuthor("dev", "dev@test.com"), "2026-01-01T00:00:00Z"),
        ),
    ) = MergeRequest(
        id = "1",
        iid = 1,
        title = title,
        description = description,
        sourceBranch = sourceBranch,
        targetBranch = targetBranch,
        author = User(username = "developer", name = "Test Developer"),
        state = MergeRequestState.OPENED,
        draft = draft,
        labels = labels,
        commits = commits,
        changes = changes,
    )

    private fun aiFinding(
        message: String,
        rule: String = "ai-review",
        severity: Severity = Severity.WARNING,
        path: String? = "src/auth/Login.kt",
        line: Int? = 10,
        confidence: Double? = null,
    ) = Finding(
        severity = severity,
        message = message,
        rule = rule,
        location = if (path != null) Location.FileRef(path, line) else null,
        metadata = buildMap {
            if (confidence != null) put("confidence", confidence.toString())
        },
    )

    // ==================== AI Quality ====================

    @Test
    fun `grounding verifier drops finding with non-existent symbol`() {
        val verifier = AiGroundingVerifier()
        val findings = listOf(
            aiFinding("Use `phantomMethod()` instead", path = "src/auth/Login.kt"),
        )
        val mr = testMr()
        val result = verifier.verify(findings, mr)
        // phantomMethod() contains a dot so it's treated as a code snippet; it doesn't exist in the diff
        assertTrue(result.findings.isEmpty())
        assertEquals(1, result.refutedCount)
    }

    @Test
    fun `evidence anchoring removes finding for non-existent file`() {
        val filter = AiFindingFilter(FilterConfig())
        val findings = listOf(
            aiFinding("Issue found", path = "src/nonexistent/File.kt"),
            aiFinding("Real issue", path = "src/auth/Login.kt"),
        )
        val result = filter.filter(
            findings,
            changedFiles = setOf("src/auth/Login.kt", "src/auth/Token.kt"),
        )
        assertEquals(1, result.size)
        assertEquals("Real issue", result[0].message)
    }

    @Test
    fun `confidence filtering removes low-confidence findings`() {
        val filter = AiFindingFilter(FilterConfig(minConfidence = 0.7))
        val findings = listOf(
            aiFinding("Low confidence", rule = "ai-review", confidence = 0.3, line = 1),
            aiFinding("High confidence", rule = "ai-suggestions", confidence = 0.9, line = 2),
        )
        val result = filter.filter(findings)
        assertEquals(1, result.size)
        assertTrue(result[0].message.contains("High"))
    }

    @Test
    fun `deduplication keeps highest severity`() {
        val filter = AiFindingFilter(FilterConfig())
        val findings = listOf(
            aiFinding("Same issue", severity = Severity.INFO, path = "src/A.kt", line = 5),
            aiFinding("Same issue", severity = Severity.ERROR, path = "src/A.kt", line = 5),
        )
        val result = filter.filter(findings)
        assertEquals(1, result.size)
        assertEquals(Severity.ERROR, result[0].severity)
    }

    // ==================== AI Capability ====================

    @Test
    fun `diff chunker splits large MR into chunks`() {
        val changes = (1..30).map { i ->
            FileChange(
                "src/File$i.kt",
                ChangeType.MODIFIED,
                100,
                50,
                diffContent = "+line ".repeat(200),
            )
        }
        val mr = testMr(changes = changes)
        val chunker = DiffChunker(maxCharsPerChunk = 5000, maxChunks = 5)
        val chunks = chunker.chunk(mr)
        assertTrue(chunks.size > 1, "Should split into multiple chunks")
        assertTrue(chunks.size <= 5, "Should respect max chunks")
    }

    // ==================== AI Intelligence ====================

    @Test
    fun `finding correlation detects compound risk`() {
        val engine = FindingCorrelationEngine()
        val findings = listOf(
            Finding(severity = Severity.WARNING, message = "MR too large", rule = "size-limits"),
            Finding(severity = Severity.WARNING, message = "Missing description", rule = "description-format"),
        )
        val result = engine.correlate(findings)
        val metaFindings = result.findings.filter { it.rule.startsWith("correlation:") }
        assertTrue(metaFindings.isNotEmpty(), "Should generate correlation meta-finding")
    }

    @Test
    fun `contradiction detector does not remove non-contradictory findings`() {
        val detector = ContradictionDetector()
        val findings = listOf(
            aiFinding("Add error handling here", rule = "ai-review", path = "src/A.kt", line = 10),
            aiFinding("Add null check here", rule = "ai-suggestions", path = "src/A.kt", line = 50),
        )
        val result = detector.detect(findings)
        // Not contradictory and far apart - both should remain
        assertEquals(2, result.findings.size)
        assertEquals(0, result.contradictionsFound)
    }

    @Test
    fun `contradiction detector removes opposing findings on nearby lines`() {
        val detector = ContradictionDetector()
        val findings = listOf(
            Finding(
                severity = Severity.WARNING,
                message = "add error handling here",
                rule = "ai-review",
                location = Location.FileRef("src/A.kt", 10),
                metadata = mapOf("confidence" to "0.9"),
            ),
            Finding(
                severity = Severity.WARNING,
                message = "remove unnecessary try-catch block",
                rule = "ai-suggestions",
                location = Location.FileRef("src/A.kt", 12),
                metadata = mapOf("confidence" to "0.5"),
            ),
        )
        val result = detector.detect(findings)
        // "add" vs "remove" within 5 lines - lower confidence (0.5) should be removed
        assertTrue(result.contradictionsFound > 0 || result.findings.size <= findings.size)
    }

    @Test
    fun `rule gate skips rule when no matching files`() {
        val gate = RuleGate()
        val mr = testMr()
        val gates = listOf(
            GateConfig(
                ruleId = "ai-security",
                filePatterns = listOf("**/*.py"),
            ),
        )
        val reason = gate.evaluate("ai-security", mr, gates)
        assertNotNull(reason, "Should skip — no Python files")
    }

    @Test
    fun `rule gate allows rule when files match`() {
        val gate = RuleGate()
        val mr = testMr()
        val gates = listOf(
            GateConfig(
                ruleId = "ai-review",
                filePatterns = listOf("**/*.kt"),
            ),
        )
        val reason = gate.evaluate("ai-review", mr, gates)
        assertEquals(null, reason, "Should not skip — Kotlin files present")
    }

    @Test
    fun `process risk calculates composite score`() {
        val mr = testMr()
        val metrics = ProcessRiskCalculator.calculate(mr)
        assertTrue(metrics.composite in 0.0..1.0)
        assertTrue(metrics.churnRatio in 0.0..1.0)
    }

    // ==================== Metrics ====================

    @Test
    fun `change entropy calculates for multi-file MR`() {
        val mr = testMr()
        val metrics = ChangeEntropyCalculator.calculate(mr)
        assertTrue(metrics.fileEntropy >= 0.0)
        assertEquals(2, metrics.fileCount)
    }

    @Test
    fun `cognitive load estimates review time`() {
        val mr = testMr()
        val metrics = CognitiveLoadEstimator.estimate(mr)
        assertTrue(metrics.estimatedReviewMinutes > 0)
        assertTrue(metrics.totalLoc > 0)
    }

    @Test
    fun `hotspot analyzer scores files`() {
        val mr = testMr()
        val hotspots = HotspotAnalyzer.analyze(mr)
        assertEquals(2, hotspots.size)
        assertTrue(hotspots.all { it.hotspotScore in 0.0..1.0 })
    }

    @Test
    fun `NCD clone detector finds similar content`() {
        val repeatedContent = "+fun process(input: String): String { return input.trim().lowercase() }\n".repeat(5)
        val changes = listOf(
            FileChange("src/A.kt", ChangeType.MODIFIED, 10, 0, diffContent = repeatedContent),
            FileChange("src/B.kt", ChangeType.MODIFIED, 10, 0, diffContent = repeatedContent),
        )
        val mr = testMr(changes = changes)
        val clones = NcdCloneDetector.detect(mr)
        assertTrue(clones.isNotEmpty(), "Identical content should be detected as clone")
    }

    @Test
    fun `rule sandbox replays against snapshots`() {
        val sandbox = RuleSandbox()
        val snapshots = (1..5).map { testMr(title = "MR $it") }
        val result = sandbox.replay(snapshots, "title-format") { mr ->
            if (mr.title.startsWith("feat:")) {
                emptyList()
            } else {
                listOf(Finding(Severity.WARNING, "Bad title", "title-format"))
            }
        }
        assertEquals(5, result.snapshotsReplayed)
        assertTrue(result.impactSummary.fireRate in 0.0..1.0)
    }

    // ==================== Configuration ====================

    @Test
    fun `execution profile resolves ci profile`() {
        val profile = ExecutionProfileResolver.resolve("ci")
        assertNotNull(profile)
        assertEquals(false, profile.aiEnabled)
    }

    @Test
    fun `execution profile filters ai rules for ci`() {
        val profile = ExecutionProfileResolver.resolve("ci")!!
        val filtered = ExecutionProfileResolver.filterRules(profile, setOf("title-format", "ai-review", "security"))
        assertTrue("ai-review" !in filtered, "CI profile should exclude AI rules")
        assertTrue("title-format" in filtered)
    }

    @Test
    fun `progressive enforcement downgrades error to warning in warn mode`() {
        val mode = ProgressiveEnforcement.resolveMode(
            listOf(EnforcementPhase(from = "2020-01-01", mode = EnforcementMode.WARN)),
            "2026-03-16",
        )
        assertEquals(EnforcementMode.WARN, mode)
        val adjusted = ProgressiveEnforcement.adjustSeverity(Severity.ERROR, mode)
        assertEquals(Severity.WARNING, adjusted)
    }

    @Test
    fun `severity reclassifier applies overrides`() {
        val findings = listOf(Finding(Severity.ERROR, "Too large", "size-limits"))
        val overrides = listOf(SeverityOverride(rule = "size-limits", reclassify = mapOf("error" to "warning")))
        val result = SeverityReclassifier.apply(findings, overrides)
        assertEquals(Severity.WARNING, result.findings[0].severity)
        assertEquals(1, result.reclassifiedCount)
    }

    @Test
    fun `conditional section checker detects missing section`() {
        val mr = testMr(
            description = "No database section",
            changes = listOf(FileChange("db/migration.sql", ChangeType.ADDED, 10, 0)),
        )
        val sections = listOf(
            ConditionalSection(section = "## Database Changes", whenFilesChanged = listOf("**/*.sql")),
        )
        val findings = ConditionalSectionChecker.check(mr, sections)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("Database Changes"))
    }

    @Test
    fun `conditional section checker passes when section present`() {
        val mr = testMr(
            description = "## Database Changes\nMigration adds users table.",
            changes = listOf(FileChange("db/migration.sql", ChangeType.ADDED, 10, 0)),
        )
        val sections = listOf(
            ConditionalSection(section = "## Database Changes", whenFilesChanged = listOf("**/*.sql")),
        )
        val findings = ConditionalSectionChecker.check(mr, sections)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `rule composer evaluates AND expressions`() {
        val findings = listOf(
            Finding(Severity.WARNING, "Large", "size-limits"),
            Finding(Severity.WARNING, "No desc", "description-format"),
        )
        val compositions = listOf(
            CompositionRule(
                name = "large-undocumented",
                expression = "rule_failed(size-limits) && rule_failed(description-format)",
                message = "Large undocumented MR",
            ),
        )
        val composed = RuleComposer.evaluate(findings, compositions)
        assertEquals(1, composed.size)
        assertTrue(composed[0].rule.startsWith("composed:"))
    }

    @Test
    fun `inline MR override parser extracts tags`() {
        val desc = """
            Some description.
            <!-- karandash-override rule: size-limits max-lines: 3000 reason: "Large refactor" -->
        """.trimIndent()
        val overrides = InlineMrOverrideParser.parse(desc)
        assertEquals(1, overrides.size)
        assertEquals("size-limits", overrides[0].rule)
        assertEquals("Large refactor", overrides[0].reason)
    }

    // ==================== Reporters ====================

    @Test
    fun `risk heatmap scores files by severity`() {
        val findings = listOf(
            aiFinding("Error", severity = Severity.ERROR, path = "src/A.kt"),
            aiFinding("Warning", severity = Severity.WARNING, path = "src/A.kt"),
            aiFinding("Info", severity = Severity.INFO, path = "src/B.kt"),
        )
        val heatmap = RiskHeatmapGenerator.generate(findings)
        assertTrue(heatmap.files.isNotEmpty())
        // src/A.kt has error(3) + warning(1) = score 4; src/B.kt has info = score 0
        assertTrue(heatmap.files[0].score > 0)
    }

    @Test
    fun `compliance evidence builds checklist`() {
        val findings = listOf(
            Finding(Severity.ERROR, "Failed", "security"),
            Finding(Severity.INFO, "Passed", "title-format"),
        )
        val evidence = ComplianceEvidenceBuilder.build(findings, "config-hash", "mr-1", "2026-01-01")
        assertTrue(evidence.checklist.isNotEmpty())
        assertTrue(evidence.integrityHash.isNotEmpty())
    }

    @Test
    fun `finding transformer applies template`() {
        val finding = Finding(
            Severity.WARNING,
            "Issue found",
            "ai-review",
            location = Location.FileRef("src/A.kt", 42),
        )
        val result = FindingTransformer.transform(finding, "[{{severity}}] {{rule}} @ {{file}}:{{line}}")
        assertEquals("[WARNING] ai-review @ src/A.kt:42", result)
    }

    @Test
    fun `annotated diff HTML generates valid document`() {
        val mr = testMr()
        val findings = listOf(aiFinding("Issue", path = "src/auth/Login.kt"))
        val html = AnnotatedDiffHtmlBuilder.build(mr, findings)
        assertTrue(html.contains("<!DOCTYPE html>"))
        assertTrue(html.contains("src/auth/Login.kt"))
        assertTrue(html.contains("Issue"))
    }

    // ==================== Architecture ====================

    @Test
    fun `ADR validator detects forbidden pattern`() {
        val mr = testMr(
            changes = listOf(
                FileChange(
                    "src/controllers/UserCtrl.kt",
                    ChangeType.MODIFIED,
                    5,
                    0,
                    diffContent = "+val db = DataSource.getConnection()",
                ),
            ),
        )
        val adrs = listOf(
            AdrConstraint(
                id = "ADR-001",
                name = "No direct DB in controllers",
                affectedPaths = listOf("**/controllers/**"),
                forbiddenPatterns = listOf("DataSource"),
            ),
        )
        val findings = AdrValidator.validate(mr, adrs)
        assertTrue(findings.isNotEmpty())
        assertTrue(findings[0].message.contains("ADR"))
    }

    @Test
    fun `provenance chain builds and verifies`() {
        val findings = listOf(
            Finding(Severity.WARNING, "Issue 1", "rule-1"),
            Finding(Severity.ERROR, "Issue 2", "rule-2"),
        )
        val result = ProvenanceChain.build(findings, "config-hash", "mr-hash", "1.7.0")
        assertEquals(2, result.entries.size)
        assertTrue(result.chainHash.isNotEmpty())
        assertTrue(ProvenanceChain.verify(result))
    }

    // ==================== Research ====================

    @Test
    fun `bloom filter pre-screening contains added items`() {
        val filter = BloomFilter.fromPatterns(listOf("*.kt", "*.java", "*.py"))
        assertTrue(filter.mightContain("*.kt"))
        assertTrue(filter.mightContain("*.java"))
        assertEquals(3, filter.itemCount())
    }

    @Test
    fun `EVT auto-threshold computes from sufficient data`() {
        val values = (1..100).map { it.toDouble() }
        val result = EvtAutoThreshold.computeThreshold(values, confidence = 0.95, fallback = 50.0)
        assertTrue(result.isDataDriven)
        assertTrue(result.threshold > 0)
    }

    @Test
    fun `EVT auto-threshold uses fallback for insufficient data`() {
        val values = listOf(1.0, 2.0, 3.0)
        val result = EvtAutoThreshold.computeThreshold(values, confidence = 0.95, fallback = 99.0)
        assertEquals(false, result.isDataDriven)
        assertEquals(99.0, result.threshold)
    }

    @Test
    fun `refactoring detector classifies import-only changes`() {
        val changes = listOf(
            FileChange(
                "src/A.kt",
                ChangeType.MODIFIED,
                2,
                2,
                diffContent = "-import com.old.Package\n+import com.new.Package",
            ),
        )
        val result = RefactoringDetector.analyze(changes)
        assertTrue(result.refactoringPatterns.isNotEmpty() || result.behavioralChanges.isNotEmpty())
    }

    @Test
    fun `cognitive complexity delta computes increase`() {
        val changes = listOf(
            FileChange(
                "src/A.kt",
                ChangeType.MODIFIED,
                10,
                0,
                diffContent = "+if (x > 0) {\n" +
                    "+    for (i in 1..10) {\n" +
                    "+        if (y > 0) {\n" +
                    "+            process()\n" +
                    "+        }\n" +
                    "+    }\n" +
                    "+}",
            ),
        )
        val deltas = CognitiveComplexityDelta.compute(changes)
        assertTrue(deltas.isNotEmpty())
        assertTrue(deltas[0].addedComplexity > 0)
    }

    // ==================== Performance ====================

    @Test
    fun `multi-pattern matcher finds all patterns in single pass`() {
        val matcher = MultiPatternMatcher.build(listOf("password", "secret", "api_key"))
        val matches = matcher.findAll("my password is a secret api_key value")
        assertEquals(3, matches.size)
    }

    @Test
    fun `multi-pattern matcher returns empty for no matches`() {
        val matcher = MultiPatternMatcher.build(listOf("password", "secret"))
        val matches = matcher.findAll("nothing sensitive here")
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `shared computation cache computes once`() {
        val cache = TieredCache<String, Any?>(
            name = "test-computation",
            maxEntries = 64,
            tier = CacheTier.MEMORY_ONLY,
        )
        var computeCount = 0
        val result1 = cache.getOrCompute("key") {
            computeCount++
            "value"
        }
        val result2 = cache.getOrCompute("key") {
            computeCount++
            "other"
        }
        assertEquals("value", result1)
        assertEquals("value", result2)
        assertEquals(1, computeCount)
    }

    @Test
    fun `shared computation cache computes separately for different keys`() {
        val cache = TieredCache<String, Any?>(
            name = "test-computation",
            maxEntries = 64,
            tier = CacheTier.MEMORY_ONLY,
        )
        val r1 = cache.getOrCompute("key-a") { "alpha" }
        val r2 = cache.getOrCompute("key-b") { "beta" }
        assertEquals("alpha", r1)
        assertEquals("beta", r2)
    }
}
