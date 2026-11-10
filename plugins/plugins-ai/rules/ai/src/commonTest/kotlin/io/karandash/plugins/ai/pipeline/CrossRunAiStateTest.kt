@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.pipeline

import io.karandash.api.module.ExtensionStateStore
import io.karandash.duration.KarandashDuration
import io.karandash.logger.NoOpLogger
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiStatePersistence
import io.karandash.plugins.ai.core.AuthorViolationState
import io.karandash.plugins.ai.core.TemporalConfig
import io.karandash.plugins.ai.core.TemporalRuleTracker
import io.karandash.plugins.ai.core.TemporalState
import io.karandash.plugins.ai.core.research.AdaptiveDefectModel
import io.karandash.plugins.ai.core.research.DefectModelState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Temporal escalation and defect-model drift state live in the AI state store, not in-process. */
class CrossRunAiStateTest {
    private class MemoryStore : ExtensionStateStore {
        val entries = mutableMapOf<String, ByteArray>()
        override val ttl: KarandashDuration = KarandashDuration.ofSeconds(60)

        override fun get(key: String): ByteArray? = entries[key]

        override fun put(
            key: String,
            content: ByteArray,
        ) {
            entries[key] = content
        }

        override fun invalidate(key: String) {
            entries.remove(key)
        }

        override fun keys(): List<String> = entries.keys.toList()
    }

    private val mr = MergeRequest(
        id = "1",
        iid = 1,
        title = "t",
        description = null,
        sourceBranch = "f",
        targetBranch = "main",
        author = User("1", "dev", "Dev"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        commits = emptyList(),
        changes = emptyList(),
    )

    private fun findings(severity: Severity) = listOf(Finding(severity = severity, message = "m", rule = "r1"))

    @Test
    fun `temporal escalation fires on a later run from persisted author history`() {
        val persistence = AiStatePersistence(MemoryStore())
        val processor = AiFindingPostProcessor(NoOpLogger)

        repeat(2) { processor.applyTemporalEscalation(findings(Severity.WARNING), mr, persistence) }
        val third = processor.applyTemporalEscalation(findings(Severity.INFO), mr, persistence)

        assertEquals(Severity.WARNING, third.single().severity)
        assertEquals("2", third.single().metadata["priorViolations"])
    }

    @Test
    fun `trackViolations false escalates without recording this run`() {
        val persistence = AiStatePersistence(MemoryStore())
        val processor = AiFindingPostProcessor(NoOpLogger)
        val readOnly = TemporalConfig(trackViolations = false)

        repeat(2) { processor.applyTemporalEscalation(findings(Severity.WARNING), mr, persistence, readOnly) }
        assertEquals(Severity.INFO, processor.applyTemporalEscalation(findings(Severity.INFO), mr, persistence).single().severity)
    }

    @Test
    fun `without a state store findings pass through unchanged`() {
        val processor = AiFindingPostProcessor(NoOpLogger)
        val input = findings(Severity.INFO)

        repeat(3) { assertEquals(input, processor.applyTemporalEscalation(input, mr, null)) }
    }

    @Test
    fun `recordViolations replaces state instead of mutating it`() {
        val initial = TemporalState(mapOf("dev" to AuthorViolationState(mapOf("r1" to listOf(1L)))))
        val tracker = TemporalRuleTracker(state = initial)

        tracker.recordViolations("dev", findings(Severity.ERROR))

        assertEquals(
            listOf(1L),
            initial.authors
                .getValue("dev")
                .ruleHistory
                .getValue("r1")
        )
        assertEquals(
            2,
            tracker
                .currentState()
                .authors
                .getValue("dev")
                .ruleHistory
                .getValue("r1")
                .size
        )
    }

    @Test
    fun `defect model statistics accumulate across runs through persistence`() {
        val persistence = AiStatePersistence(MemoryStore())
        repeat(3) {
            val model = AdaptiveDefectModel(initial = persistence.loadDefectModelState())
            model.update(0.5)
            persistence.saveDefectModelState(model.snapshot())
        }

        assertEquals(3, persistence.loadDefectModelState().sampleCount)
    }

    @Test
    fun `defect model restored from snapshot detects drift a fresh model would not`() {
        val warmed = AdaptiveDefectModel()
        repeat(20) { warmed.update(if (it % 2 == 0) 0.1 else 0.12) }
        val restored = AdaptiveDefectModel(initial = warmed.snapshot())

        assertTrue(restored.update(0.95).driftDetected)
        assertEquals(false, AdaptiveDefectModel(initial = DefectModelState()).update(0.95).driftDetected)
    }
}
