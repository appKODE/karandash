package io.karandash.contracts.ai

import io.karandash.contracts.ai.AiReviewHarness.Companion.ADDED_LINE
import io.karandash.contracts.ai.AiReviewHarness.Companion.CONTEXT_LINE
import io.karandash.contracts.ai.AiReviewHarness.Companion.MODEL
import io.karandash.contracts.ai.AiReviewHarness.Companion.PROVIDER_ID
import io.karandash.contracts.ai.AiReviewHarness.Companion.aiFindings
import io.karandash.contracts.ai.AiReviewHarness.Companion.defaultMr
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Location
import io.karandash.models.support.WellKnownMetadataKeys
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * AIR end-to-end contract: YAML -> ConfigMapParser ->
 * ValidationPipeline -> real review-comments analyzer -> OpenAiProvider over a MockEngine.
 * Covers priority truncation, config-change re-runs, inline placement and
 * per-finding provider/model provenance.
 */
class AiReviewEndToEndContractTest {
    private val placementComments = listOf(
        FakeComment("src/A.kt", ADDED_LINE, "Null dereference via !!"),
        FakeComment("src/A.kt", CONTEXT_LINE, "Context-line remark"),
        FakeComment("src/Ghost.kt", 3, "Ghost file remark"),
    )

    @Test
    fun `AIR placement - added lines are annotated with new-file numbers in the LLM request`() =
        runTest(timeout = 2.minutes) {
            val harness = AiReviewHarness(placementComments)
            harness.run()
            assertTrue(harness.requestBodies.isNotEmpty(), "setup: no LLM request reached the MockEngine")
            assertTrue(harness.allRequests.contains("L$ADDED_LINE(+) "), "AIR placement: 'L$ADDED_LINE(+)' anchor missing")
        }

    @Test
    fun `AIR placement - comment on an added line lands on FileRef src-A-kt line 12`() =
        runTest(timeout = 2.minutes) {
            val findings = aiFindings(AiReviewHarness(placementComments).run())
            assertTrue(
                findings.any { it.location == Location.FileRef("src/A.kt", ADDED_LINE) },
                "AIR placement: no finding at src/A.kt:$ADDED_LINE; got ${findings.map { it.location }}",
            )
        }

    @Test
    fun `AIR placement - comment on a file outside the MR is dropped`() =
        runTest(timeout = 2.minutes) {
            val findings = aiFindings(AiReviewHarness(placementComments).run())
            assertTrue(findings.isNotEmpty(), "setup: pipeline produced no findings at all")
            assertTrue(
                findings.none { (it.location as? Location.FileRef)?.path == "src/Ghost.kt" },
                "AIR placement: finding on src/Ghost.kt (not in MR) was kept",
            )
        }

    @Test
    fun `context-line comment is snapped or demoted when findingValidator strict is false`() =
        runTest(timeout = 2.minutes) {
            val findings = aiFindings(
                AiReviewHarness(placementComments).run("  contextQuality:\n    findingValidator:\n      strict: false"),
            )
            assertTrue(
                findings.any { it.message.contains("Context-line remark") },
                "context-line finding dropped although contextQuality.findingValidator.strict=false " +
                    "(expected snap to +$ADDED_LINE or path-only demotion)",
            )
        }

    @Test
    fun `AIR provenance - every AI finding records the model`() =
        runTest(timeout = 2.minutes) {
            val findings = aiFindings(AiReviewHarness().run())
            assertTrue(findings.isNotEmpty(), "setup: pipeline produced no findings at all")
            findings.forEach {
                assertEquals(MODEL, it.metadata[WellKnownMetadataKeys.AI_MODEL], "AIR provenance: model not recorded on $it")
            }
        }

    @Test
    fun `AIR provenance - every AI finding records the provider`() =
        runTest(timeout = 2.minutes) {
            val findings = aiFindings(AiReviewHarness().run())
            assertTrue(findings.isNotEmpty(), "setup: pipeline produced no findings at all")
            findings.forEach {
                assertEquals(
                    PROVIDER_ID,
                    it.metadata[AI_PROVIDER_KEY],
                    "AIR provenance: provider id not recorded per finding (metadata keys: ${it.metadata.keys})",
                )
            }
        }

    /**
     * A single over-budget file has a large, sparse first hunk and a small, dense second
     * hunk. PRIORITY (HunkAwareTruncator) keeps the dense hunk; the legacy sequential path the
     * dispatcher uses today cuts the file at the char budget and loses it. Cross-file priority is
     * not tested here: DiffChunker already sorts files by priority, which masks this across files.
     */
    @Test
    fun `over-budget file uses PRIORITY hunk-aware truncation and keeps the dense hunk`() =
        runTest(timeout = 2.minutes) {
            val sparseHunk = "@@ -1,$SPARSE_CONTEXT_LINES +1,${SPARSE_CONTEXT_LINES + 1} @@\n" +
                (1..SPARSE_CONTEXT_LINES).joinToString("\n") { " val padding$it = Unit // unchanged filler" } +
                "\n+val touched = 1"
            val denseHunk = "@@ -100,2 +101,3 @@\n fun auth() = Unit\n+fun bypass() = HIGH_PRIORITY_MARKER\n fun end() = Unit"
            val auth = FileChange(
                path = "src/main/kotlin/Auth.kt",
                changeType = ChangeType.MODIFIED,
                additions = 2,
                diffContent = "$sparseHunk\n$denseHunk",
            )
            val harness = AiReviewHarness()
            harness.run(
                aiBlockYaml = "  limits:\n    maxDiffCharsTotal: 600\n    maxDiffCharsPerFile: 600\n" +
                    "  contextQuality:\n    truncation:\n      strategy: priority",
                mr = defaultMr(listOf(auth)),
            )
            assertTrue(harness.requestBodies.isNotEmpty(), "setup: no LLM request reached the MockEngine")
            assertTrue(
                harness.allRequests.contains("HIGH_PRIORITY_MARKER"),
                "dense hunk truncated away — dispatcher used sequential char truncation, not PRIORITY",
            )
        }

    @Test
    fun `changed ai config on an unchanged MR re-runs the LLM`() =
        runTest(timeout = 2.minutes) {
            val harness = AiReviewHarness()
            harness.run("  responseLanguage: en")
            val afterFirst = harness.requestBodies.size
            assertTrue(afterFirst > 0, "setup: no LLM request on the first run")
            harness.run(
                "  responseLanguage: en\n  scopes:\n    security:\n      paths: [\"src/**\"]\n" +
                    "      instructions: \"changed config\"",
            )
            assertTrue(
                harness.requestBodies.size > afterFirst,
                "changed ai config served cached findings without calling the LLM (cache key ignores config)",
            )
        }

    private companion object {
        /** Proposed provenance metadata key; absent today. */
        const val AI_PROVIDER_KEY = "aiProvider"
        const val SPARSE_CONTEXT_LINES = 40
    }
}
