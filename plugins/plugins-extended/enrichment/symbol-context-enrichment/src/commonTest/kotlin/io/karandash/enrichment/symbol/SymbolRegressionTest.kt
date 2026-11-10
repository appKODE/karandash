package io.karandash.enrichment.symbol

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.platform.ProcessResult
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/** a large merge request must not spawn one ast-grep process per pattern per file without limit. */
class SymbolRegressionTest {
    @Test
    fun `symbol scan stops after the file budget`() =
        runTest {
            var scans = 0
            val runner: ProcessRunner = { command, _, _ ->
                if (command.last() != "--version") scans++
                ProcessResult(0, "", "")
            }
            val provider = SymbolContextEnrichmentProvider(runner)
            assertTrue(provider.initialize(TestPluginContext()))

            val changes = (1..250).map { FileChange(path = "src/F$it.kt", changeType = ChangeType.MODIFIED) }
            provider.enrich(createTestMergeRequest(changes = changes), JsonObject(emptyMap()))

            assertEquals(MAX_SCANNED_FILES * 2, scans)
        }

    @Test
    fun `symbol scan stops when the time budget is used up`() =
        runTest {
            var scans = 0
            val runner: ProcessRunner = { command, _, _ ->
                if (command.last() != "--version") scans++
                ProcessResult(0, "", "")
            }
            val provider = SymbolContextEnrichmentProvider(runner, timeBudget = Duration.ZERO)
            assertTrue(provider.initialize(TestPluginContext()))

            val changes = (1..5).map { FileChange(path = "src/F$it.kt", changeType = ChangeType.MODIFIED) }
            provider.enrich(createTestMergeRequest(changes = changes), JsonObject(emptyMap()))

            assertEquals(0, scans)
        }
}
