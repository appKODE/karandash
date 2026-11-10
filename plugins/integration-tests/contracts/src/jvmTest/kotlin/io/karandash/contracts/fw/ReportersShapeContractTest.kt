package io.karandash.contracts.fw

import io.karandash.contracts.support.createFwTempDir
import io.karandash.contracts.support.runFwPipeline
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `reporters:` entries are mis-read by `FormatOutputPlanner.selectConfiguredOutputs`:
 * flat keys are ignored (only `config:` is read), format shorthand `sarif: true` looks for an output named
 * `sarif`, `x: false` is not honoured, and output-named entries raise a false E0303 in `MissingModuleDetector`.
 */
class ReportersShapeContractTest {
    @Test
    fun `format shorthand sarif true emits SARIF`() =
        runTest {
            val run = runFwPipeline(
                dir = createFwTempDir(),
                configYaml = """
                    reporters:
                      sarif: true
                """,
            )
            assertTrue("sarif-2.1.0.json" in run.stdout, "`reporters: sarif: true` produced no SARIF output")
        }

    @Test
    fun `flat output keys on a reporters entry are honoured`() =
        runTest {
            val dir = createFwTempDir()
            val target = Path(dir, "report.json")
            runFwPipeline(
                dir = dir,
                configYaml = """
                    reporters:
                      json:
                        format: json
                        output: filesystem
                        output-file: $target
                """,
            )
            assertTrue(SystemFileSystem.exists(target), "flat `output-file` key ignored; $target was not written")
        }

    @Test
    fun `reporters entry set to false is not run`() =
        runTest {
            val run = runFwPipeline(
                dir = createFwTempDir(),
                configYaml = """
                    reporters:
                      stdout: false
                """,
            )
            assertFalse("\"passed\"" in run.stdout, "`reporters: stdout: false` still printed a JSON report")
        }

    @Test
    fun `output-named reporters entry is not an unknown module under on-missing fail`() =
        runTest {
            val run = runFwPipeline(
                dir = createFwTempDir(),
                configYaml = """
                    reporters:
                      stdout:
                        format: json
                """,
            ) { it.copy(onMissingPlugin = "fail") }
            assertTrue(
                run.result.isSuccess && "\"passed\"" in run.stdout,
                "`reporters: stdout` rejected as unknown module (false E0303): ${run.result.exceptionOrNull()?.message}",
            )
        }
}
