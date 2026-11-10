package io.karandash.scripts

import io.karandash.api.platform.SubprocessBudget
import io.karandash.duration.KarandashDuration
import io.karandash.interpolation.KarandashEnvSecurity
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.platform.InspectionDenial
import io.karandash.platform.PlatformEnvironment
import io.karandash.platform.ProcessResult
import io.karandash.platform.ProcessTimeouts
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubprocessScriptExecutorTest {
    private class FakeRunner(
        private val denial: InspectionDenial = InspectionDenial.Applied,
        private val result: () -> ProcessResult,
    ) {
        val commands = mutableListOf<List<String>>()
        val workingDirs = mutableListOf<String>()
        val timeouts = mutableListOf<Int?>()
        val events = mutableListOf<String>()
        val unsetEnvs = mutableListOf<Set<String>>()

        fun harden(): InspectionDenial {
            events.add("harden")
            return denial
        }

        suspend fun run(
            command: List<String>,
            workingDir: String,
            timeoutSeconds: Int?,
            unsetEnv: Set<String>,
        ): ProcessResult {
            events.add("run")
            unsetEnvs.add(unsetEnv)
            commands.add(command)
            workingDirs.add(workingDir)
            timeouts.add(timeoutSeconds)
            return result()
        }
    }

    private fun payload(
        findings: String,
        envReads: String = "[]",
    ): String = SubprocessScriptExecutor.RESULT_MARKER + """{"findings":$findings,"envReads":$envReads}"""

    private suspend fun runWith(
        runner: FakeRunner,
        context: TestPluginContext = TestPluginContext(),
        entry: ScriptEntryConfig = ScriptEntryConfig(path = "check.mjs"),
        source: String = "warn('x')",
    ) = SubprocessScriptExecutor(context, runner::run, runner::harden).run(entry, TestFixtures.minimalMr(), source, "/work")

    @Test
    fun `runs node with the bridge script in the working directory`() =
        runTest {
            val runner = FakeRunner { ProcessResult(0, payload("[]"), "") }

            runWith(runner, source = "karandash.warn(\"quoted\")\n")

            val command = runner.commands.single()
            assertEquals(listOf("node", "-e"), command.take(2))
            assertTrue(command[2].contains("let userSource = \"karandash.warn(\\\"quoted\\\")\\n\";"), command[2])
            assertTrue(command[2].contains("filename: \"check.mjs\""), command[2])
            assertEquals("/work", runner.workingDirs.single())
        }

    @Test
    fun `a script run spends the subprocess budget and a spent budget skips node`() =
        runTest {
            val runner = FakeRunner { ProcessResult(0, payload("[]"), "") }
            val context = TestPluginContext(subprocessBudget = SubprocessBudget(maxCalls = 1))

            runWith(runner, context)
            runWith(runner, context)

            assertEquals(1, runner.commands.size)
            assertEquals(0, context.subprocessBudget.remainingCalls)
            assertEquals(
                listOf("Script 'check.mjs' skipped: the run's subprocess budget (1 calls, 30000 ms) is spent"),
                context.warnings,
            )
        }

    @Test
    fun `denies process inspection and drops denylisted vars from the child env before node starts`() =
        runTest {
            val runner = FakeRunner { ProcessResult(0, payload("[]"), "") }

            runWith(runner)

            assertEquals(listOf("harden", "run"), runner.events)
            assertEquals(KarandashEnvSecurity.DENYLIST, runner.unsetEnvs.single())
        }

    @Test
    fun `failed inspection denial warns about the risk and still runs the script`() =
        runTest {
            val runner = FakeRunner(InspectionDenial.Failed) { ProcessResult(0, payload("[]"), "") }
            val context = TestPluginContext()

            runWith(runner, context)

            assertEquals(listOf("harden", "run"), runner.events)
            assertEquals(1, context.warnings.size)
            assertTrue(context.warnings.single().contains("same-user"), context.warnings.single())
        }

    @Test
    fun `applied or not applicable inspection denial does not warn`() =
        runTest {
            for (denial in listOf(InspectionDenial.Applied, InspectionDenial.NotApplicable)) {
                val context = TestPluginContext()
                runWith(FakeRunner(denial) { ProcessResult(0, payload("[]"), "") }, context)
                assertEquals(emptyList(), context.warnings)
            }
        }

    @Test
    fun `passes the entry timeout to the process rounded up to whole seconds`() =
        runTest {
            val runner = FakeRunner { ProcessResult(0, payload("[]"), "") }

            runWith(runner, entry = ScriptEntryConfig(path = "a.mjs", timeout = KarandashDuration.ofMillis(1500)))
            runWith(runner, entry = ScriptEntryConfig(path = "b.mjs", timeout = KarandashDuration.ofMillis(10)))
            runWith(runner, entry = ScriptEntryConfig(path = "c.mjs"))

            assertEquals(listOf<Int?>(2, 1, 30), runner.timeouts)
        }

    @Test
    fun `timed out process exits 137 and warns and returns no findings`() =
        runTest {
            val context = TestPluginContext()
            val runner = FakeRunner { ProcessResult(ProcessTimeouts.EXIT_CODE, "", "Process timed out after 1s") }

            val findings = runWith(runner, context)

            assertEquals(137, ProcessTimeouts.EXIT_CODE)
            assertTrue(findings.isEmpty())
            assertEquals(
                listOf("Script 'check.mjs' subprocess exited with code 137: Process timed out after 1s"),
                context.warnings,
            )
        }

    @Test
    fun `non-zero exit warns with stderr and drops output`() =
        runTest {
            val context = TestPluginContext()
            val runner = FakeRunner { ProcessResult(2, payload("""[{"severity":"error","message":"m"}]"""), "boom") }

            val findings = runWith(runner, context)

            assertTrue(findings.isEmpty())
            assertEquals(listOf("Script 'check.mjs' subprocess exited with code 2: boom"), context.warnings)
        }

    @Test
    fun `process launch failure warns and returns no findings`() =
        runTest {
            val context = TestPluginContext()
            val runner = FakeRunner { throw IllegalStateException("node not found") }

            val findings = runWith(runner, context)

            assertTrue(findings.isEmpty())
            assertEquals(listOf("Script 'check.mjs' subprocess failed: node not found"), context.warnings)
        }

    @Test
    fun `parses findings with severities and locations`() =
        runTest {
            val json =
                """[
                  {"severity":"error","message":"e","file":"a.kt","line":3},
                  {"severity":"warning","message":"w","file":"b.kt","line":null},
                  {"severity":"info","message":"i","file":null,"line":null},
                  {"severity":"odd","message":"defaults to warning"},
                  {"severity":"error"}
                ]"""
            val runner = FakeRunner { ProcessResult(0, payload(json), "") }

            val findings = runWith(runner)

            assertEquals(
                listOf(Severity.ERROR, Severity.WARNING, Severity.INFO, Severity.WARNING),
                findings.map { it.severity },
            )
            assertEquals(listOf("e", "w", "i", "defaults to warning"), findings.map { it.message })
            assertTrue(findings.all { it.rule == "script" })
            assertEquals(Location.FileRef(path = "a.kt", line = 3), findings[0].location)
            assertEquals(Location.FileRef(path = "b.kt", line = null), findings[1].location)
            assertEquals(null, findings[2].location)
        }

    @Test
    fun `console output from the script does not hide its findings`() =
        runTest {
            val context = TestPluginContext()
            val output = "debug line\n{\"not\":\"findings\"}\n" + payload("""[{"severity":"info","message":"kept"}]""")
            val runner = FakeRunner { ProcessResult(0, output, "") }

            val findings = runWith(runner, context)

            assertEquals(listOf("kept"), findings.map { it.message })
            assertTrue(context.warnings.isEmpty(), context.warnings.toString())
        }

    @Test
    fun `missing result payload warns and returns no findings`() =
        runTest {
            val context = TestPluginContext()
            val runner = FakeRunner { ProcessResult(0, "only console output", "") }

            val findings = runWith(runner, context)

            assertTrue(findings.isEmpty())
            assertEquals(listOf("Script 'check.mjs' produced no result payload"), context.warnings)
        }

    @Test
    fun `malformed result payload warns and returns no findings`() =
        runTest {
            val context = TestPluginContext()
            val runner = FakeRunner { ProcessResult(0, payload("{not json"), "") }

            val findings = runWith(runner, context)

            assertTrue(findings.isEmpty())
            assertTrue(context.warnings.single().startsWith("Failed to parse script results"), context.warnings.toString())
        }

    @Test
    fun `declared env drops undeclared and denylisted names in the bridge before user code runs`() =
        runTest {
            val runner = FakeRunner { ProcessResult(0, payload("[]"), "") }

            runWith(runner, entry = ScriptEntryConfig(path = "check.mjs", env = listOf("JIRA_TOKEN", "HOME")))

            val bridge = runner.commands.single()[2]
            assertTrue(bridge.contains("const allowedEnv = [\"JIRA_TOKEN\"];"), bridge)
            assertTrue(bridge.indexOf("allowedEnv") < bridge.indexOf("vm.runInContext"), bridge)
            val present = PlatformEnvironment.names()
            assertEquals(KarandashEnvSecurity.DENYLIST + (present - "JIRA_TOKEN"), runner.unsetEnvs.single())
        }

    @Test
    fun `exec unset set in declared mode is the denylist plus every present undeclared name`() {
        val entry = ScriptEntryConfig(path = "check.mjs", env = listOf("JIRA_TOKEN", "HOME", "ABSENT"))

        val unset = SubprocessScriptExecutor.execUnsetEnv(entry, setOf("JIRA_TOKEN", "GITLAB_TOKEN", "HOME", "CI"))

        assertEquals(KarandashEnvSecurity.DENYLIST + setOf("GITLAB_TOKEN", "CI"), unset)
    }

    @Test
    fun `exec unset set keeps a declared name present in the environment`() {
        val entry = ScriptEntryConfig(path = "check.mjs", env = listOf("JIRA_TOKEN"))

        val unset = SubprocessScriptExecutor.execUnsetEnv(entry, setOf("JIRA_TOKEN"))

        assertTrue("JIRA_TOKEN" !in unset, unset.toString())
    }

    @Test
    fun `exec unset set without declared env is the denylist only`() {
        val unset = SubprocessScriptExecutor.execUnsetEnv(ScriptEntryConfig(path = "check.mjs"), setOf("GITLAB_TOKEN"))

        assertEquals(KarandashEnvSecurity.DENYLIST, unset)
    }

    @Test
    fun `exec unset set with an empty declared env removes every present name`() {
        val entry = ScriptEntryConfig(path = "check.mjs", env = emptyList())

        val unset = SubprocessScriptExecutor.execUnsetEnv(entry, setOf("CI", "GITLAB_TOKEN"))

        assertEquals(KarandashEnvSecurity.DENYLIST + setOf("CI", "GITLAB_TOKEN"), unset)
    }

    @Test
    fun `undeclared env leaves the allowlist off in the bridge`() =
        runTest {
            val runner = FakeRunner { ProcessResult(0, payload("[]"), "") }

            runWith(runner)

            assertTrue(runner.commands.single()[2].contains("const allowedEnv = null;"), runner.commands.single()[2])
        }

    @Test
    fun `undeclared env reads warn once with sorted non-denylisted names only`() =
        runTest {
            val context = TestPluginContext()
            val runner = FakeRunner { ProcessResult(0, payload("[]", """["JIRA_TOKEN","HOME","CI_JOB_TOKEN","JIRA_TOKEN"]"""), "") }

            runWith(runner, context)

            assertEquals(
                listOf(
                    "Script 'check.mjs' read undeclared env vars [CI_JOB_TOKEN, JIRA_TOKEN]; declare them in " +
                        "scripts[].env — undeclared reads will be stripped in the next release",
                ),
                context.warnings,
            )
        }

    @Test
    fun `undeclared script that read no env vars does not warn`() =
        runTest {
            val context = TestPluginContext()

            runWith(FakeRunner { ProcessResult(0, payload("[]", """["HOME"]"""), "") }, context)

            assertEquals(emptyList(), context.warnings)
        }

    @Test
    fun `declared script does not warn about env reads`() =
        runTest {
            val context = TestPluginContext()
            val runner = FakeRunner { ProcessResult(0, payload("[]", """["JIRA_TOKEN"]"""), "") }

            runWith(runner, context, ScriptEntryConfig(path = "check.mjs", env = emptyList()))

            assertEquals(emptyList(), context.warnings)
        }

    @Test
    fun `undeclared env warning drops malformed payload names and keeps valid ones`() =
        runTest {
            val context = TestPluginContext()
            val names = """["JIRA_TOKEN","BAD\u001b[31m","A\nB","","${"A".repeat(129)}","9X"]"""

            runWith(FakeRunner { ProcessResult(0, payload("[]", names), "") }, context)

            val warning = context.warnings.single()
            assertTrue("[JIRA_TOKEN]" in warning, warning)
            assertTrue(warning.none { it == '\u001b' || it == '\n' }, warning)
        }

    @Test
    fun `undeclared env warning caps a thousand payload names`() =
        runTest {
            val context = TestPluginContext()
            val names = (0 until 1000).joinToString(",", "[", "]") { "\"VAR_$it\"" }

            runWith(FakeRunner { ProcessResult(0, payload("[]", names), "") }, context)

            assertTrue("and 950 more" in context.warnings.single(), context.warnings.single())
        }

    @Test
    fun `denylist match is case insensitive for declared env and warnings`() =
        runTest {
            val runner = FakeRunner { ProcessResult(0, payload("[]", """["Home","JIRA_TOKEN"]"""), "") }
            val context = TestPluginContext()

            runWith(runner, context, ScriptEntryConfig(path = "check.mjs", env = listOf("Path", "Home", "JIRA_TOKEN")))
            assertTrue(runner.commands.single()[2].contains("const allowedEnv = [\"JIRA_TOKEN\"];"), runner.commands.single()[2])

            val undeclared = TestPluginContext()
            runWith(runner, undeclared)
            assertTrue("[JIRA_TOKEN]" in undeclared.warnings.single(), undeclared.warnings.toString())
        }

    @Test
    fun `exec unset set includes denylisted names in other case`() {
        val unset = SubprocessScriptExecutor.execUnsetEnv(ScriptEntryConfig(path = "check.mjs"), setOf("Path", "home", "CI"))

        assertEquals(KarandashEnvSecurity.DENYLIST + setOf("Path", "home"), unset)
    }
}
