package io.karandash.eval

import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Full-pipeline replay: cassettes go through rule module, AI extension, markdown format and platform output. */
class EvalReplayTest {
    private val runner = EvalRunner()

    @Test
    fun `full pipeline replay is deterministic`() =
        runTest {
            val first = runner.replayPipeline()
            val second = runner.replayPipeline()
            assertEquals(first.map { it.caseId }, second.map { it.caseId })
            first.zip(second).forEach { (a, b) ->
                assertEquals(a.prompts, b.prompts, "${a.caseId}: outgoing prompts differ between runs")
                assertEquals(a.summary, b.summary, "${a.caseId}: rendered summary differs between runs")
                assertEquals(a.inlineNotes, b.inlineNotes, "${a.caseId}: inline notes differ between runs")
            }
        }

    @Test
    fun `replay reaches rendered inline notes`() =
        runTest {
            val replay = runner.replayPipeline().single { it.caseId == "01-user-lookup-bugs" }
            assertTrue(replay.prompts.isNotEmpty(), "no outgoing prompt captured")
            assertTrue(replay.summary.isNotBlank(), "no summary rendered")
            assertTrue(replay.inlineNotes.isNotEmpty(), "no inline note rendered: ${replay.counters}")
            assertTrue(replay.inlineNotes.all { it.file == "src/main/kotlin/users/UserRepository.kt" }, "${replay.inlineNotes}")
        }

    @Test
    fun `replay matches the golden snapshots`() =
        runTest {
            val mismatches = runner.replayPipeline().mapNotNull { ReplaySnapshot.check(Path("src/commonTest/resources/eval/replay"), it) }
            assertTrue(
                mismatches.isEmpty(),
                mismatches.joinToString("\n", postfix = "\nRun with -Dkarandash.updateGolden=true and review the diff.")
            )
        }
}
