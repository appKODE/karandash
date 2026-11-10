package io.karandash.contracts.fw

import io.karandash.contracts.support.FwFakePlatformService
import io.karandash.contracts.support.createFwTempDir
import io.karandash.contracts.support.runFwPipeline
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * With platform context (CI / live mode) an explicit `-f sarif` is paired with
 * the platform output instead of stdout (`FormatOutputPlanner.resolvePrimaryOutput`), so stdout gets 0 bytes.
 */
class ExplicitFormatInCiContractTest {
    @Test
    fun `explicit sarif format with platform context is printed to stdout`() =
        runTest {
            val run = runFwPipeline(
                dir = createFwTempDir(),
                configYaml = """
                    rules:
                      conflict: {}
                """,
                extraModules = listOf(FwFakePlatformService()),
            ) {
                it.copy(
                    mrDataStr = null,
                    platform = FwFakePlatformService.ID,
                    project = "group/project",
                    mrId = "1",
                    format = "sarif",
                    explicitFormat = true,
                )
            }
            assertTrue(
                "sarif-2.1.0.json" in run.stdout,
                "explicit -f sarif under platform context wrote no SARIF to stdout " +
                    "(result=${run.result.exceptionOrNull()}); stdout was: ${run.stdout.take(STDOUT_PREVIEW)}",
            )
        }

    private companion object {
        const val STDOUT_PREVIEW = 300
    }
}
