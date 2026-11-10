package io.karandash.scripts

import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SandboxDslFindingsTest {
    private suspend fun run(
        source: String,
        context: TestPluginContext = TestPluginContext(),
    ) = withContext(Dispatchers.Default) {
        SandboxScriptExecutor(context).run(ScriptEntryConfig(path = "check.js"), TestFixtures.minimalMr(), source)
    }

    @Test
    fun `dsl and danger globals map to severities and locations`() =
        runTest {
            val findings =
                run(
                    """
                    karandash.fail('f', 'a.kt', 3);
                    karandash.warn('w', 'b.kt', 'x');
                    karandash.message('m');
                    warn('dw'); fail('df'); message('dm'); markdown('md');
                    """.trimIndent(),
                )

            assertEquals(
                listOf(
                    Triple(Severity.ERROR, "f", Location.FileRef("a.kt", 3)),
                    Triple(Severity.WARNING, "w", Location.FileRef("b.kt", null)),
                    Triple(Severity.INFO, "m", null),
                    Triple(Severity.WARNING, "dw", null),
                    Triple(Severity.ERROR, "df", null),
                    Triple(Severity.INFO, "dm", null),
                    Triple(Severity.INFO, "md", null),
                ),
                findings.map { Triple(it.severity, it.message, it.location) },
            )
            assertTrue(findings.all { it.rule == "script" })
        }

    @Test
    fun `optional fix argument becomes the suggestion and one argument calls keep none`() =
        runTest {
            val findings =
                run(
                    """
                    karandash.fail('f', 'a.kt', 3, 'Rename a.kt');
                    karandash.warn('w', null, null, 'Add tests');
                    karandash.message('m');
                    fail('df', null, null, 'Fix it'); warn('dw');
                    """.trimIndent(),
                )

            assertEquals(
                listOf("f" to "Rename a.kt", "w" to "Add tests", "m" to null, "df" to "Fix it", "dw" to null),
                findings.map { it.message to it.suggestion },
            )
            assertEquals(Location.FileRef("a.kt", 3), findings.first().location)
        }

    @Test
    fun `malformed or unknown raw findings are dropped or default to warning`() =
        runTest {
            val findings =
                run(
                    """
                    __karandash_finding('not json');
                    __karandash_finding('{"severity":"error"}');
                    __karandash_finding('{"severity":"odd","message":"o"}');
                    """.trimIndent(),
                )

            assertEquals(listOf(Severity.WARNING to "o"), findings.map { it.severity to it.message })
        }

    @Test
    fun `env and log go through the plugin context and script errors become warnings`() =
        runTest {
            val context = TestPluginContext(envVars = mapOf("CI" to "yes"))

            val findings = run("karandash.log(karandash.env('CI') + '-' + karandash.env('SECRET'));\nundefinedFn();", context)

            assertTrue(findings.isEmpty())
            assertEquals(listOf("yes-null"), context.logs)
            assertTrue(context.warnings.first().startsWith("Script 'check.js' failed"), context.warnings.toString())
            assertTrue(
                context.warnings.last().startsWith("Script 'check.js' read undeclared env vars [CI, SECRET]"),
                context.warnings.toString()
            )
        }
}
