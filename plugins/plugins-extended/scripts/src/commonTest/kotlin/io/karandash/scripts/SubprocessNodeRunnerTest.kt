package io.karandash.scripts

import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.platform.PlatformProcess
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Runs [SubprocessScriptExecutor] with its default runner, so a real `node` process; skipped when node is absent. */
class SubprocessNodeRunnerTest {
    private fun nodeAvailable(): Boolean =
        try {
            PlatformProcess.execute(listOf("node", "--version")).isSuccess
        } catch (ignored: IOException) {
            false
        }

    private suspend fun runNode(
        source: String,
        env: List<String>? = null,
        context: TestPluginContext = TestPluginContext(),
    ) = SubprocessScriptExecutor(context)
        .run(ScriptEntryConfig(path = "check.mjs", env = env), TestFixtures.minimalMr(), source, ".")

    @Test
    fun `default runner executes the script with node and returns its findings`() =
        runTest(timeout = 2.minutes) {
            if (!nodeAvailable()) return@runTest

            val findings =
                runNode(
                    "import { danger } from 'danger'\n" +
                        "karandash.fail('title ' + karandash.mr.title, 'a.kt', 3)\nconsole.log('noise')\nmessage('hi')",
                )

            assertEquals(listOf(Severity.ERROR, Severity.INFO), findings.map { it.severity })
            assertEquals(listOf("title feat: test MR", "hi"), findings.map { it.message })
            assertEquals(Location.FileRef(path = "a.kt", line = 3), findings.first().location)
        }

    @Test
    fun `optional fix argument becomes the suggestion in the node bridge`() =
        runTest(timeout = 2.minutes) {
            if (!nodeAvailable()) return@runTest

            val findings =
                runNode(
                    "karandash.fail('f', 'a.kt', 3, 'Rename a.kt')\nkarandash.warn('w')\n" +
                        "fail('df', null, null, 'Fix it')\nmessage('dm')",
                )

            assertEquals(
                listOf("f" to "Rename a.kt", "w" to null, "df" to "Fix it", "dm" to null),
                findings.map { it.message to it.suggestion },
            )
        }

    @Test
    fun `default runner reports a script error as a finding`() =
        runTest(timeout = 2.minutes) {
            if (!nodeAvailable()) return@runTest

            val findings = runNode("throw new Error('kaput')")

            assertEquals(listOf("Script error: kaput"), findings.map { it.message })
        }

    @Test
    fun `denylisted env vars are hidden from the script`() =
        runTest(timeout = 2.minutes) {
            if (!nodeAvailable()) return@runTest

            val findings = runNode("warn('HOME=' + process.env.HOME); warn('PATH=' + karandash.env('PATH'))")

            assertEquals(listOf("HOME=undefined", "PATH=null"), findings.map { it.message })
        }

    @Test
    fun `denylisted env vars are absent from the initial environment of the node process`() =
        runTest(timeout = 2.minutes) {
            if (!nodeAvailable()) return@runTest

            // The needle is built at runtime so the bridge script on node's own command line cannot match it.
            val findings =
                runNode(
                    "const needle = new RegExp('(^|[\\\\0 ])' + 'HO' + 'ME=');\n" +
                        "const env = process.platform === 'linux'\n" +
                        "  ? require('fs').readFileSync('/proc/self/environ', 'latin1')\n" +
                        "  : require('child_process').execFileSync('/bin/ps', " +
                        "['-E', '-ww', '-o', 'command=', '-p', String(process.pid)], { encoding: 'latin1' });\n" +
                        "warn('denylisted var in initial env=' + needle.test(env))",
                )

            assertEquals(listOf("denylisted var in initial env=false"), findings.map { it.message })
        }

    @Test
    fun `no ancestor environment with denylisted vars is readable before the guarded karandash process on Linux`() =
        runTest(timeout = 2.minutes) {
            if (!nodeAvailable()) return@runTest

            val findings =
                runNode(
                    "if (process.platform !== 'linux') { warn('guarded') } else {\n" +
                        "const fs = require('fs'); const needle = ['HOME', '='].join('');\n" +
                        "let pid = process.ppid; let verdict = 'unguarded';\n" +
                        "while (pid > 1) {\n" +
                        "  let env; try { env = fs.readFileSync('/proc/' + pid + '/environ', 'latin1') } " +
                        "catch (e) { verdict = 'guarded'; break }\n" +
                        "  if (env.split('\\0').some((v) => v.startsWith(needle))) { verdict = 'leak ' + pid; break }\n" +
                        "  pid = Number(/PPid:\\s*(\\d+)/.exec(fs.readFileSync('/proc/' + pid + '/status', 'latin1'))[1]);\n" +
                        "}\nwarn(verdict) }",
                )

            assertEquals(listOf("guarded"), findings.map { it.message })
        }

    @Test
    fun `other env vars stay visible to the script`() =
        runTest(timeout = 2.minutes) {
            if (!nodeAvailable()) return@runTest

            val findings = runNode("warn('set=' + (karandash.env('KARANDASH_UPDATE_GOLDEN') !== null))")

            assertEquals(listOf("set=true"), findings.map { it.message })
        }

    // KARANDASH_UPDATE_GOLDEN and KARANDASH_UPDATE_BASELINE are set in every test process (TestEnvironment).
    private val probe =
        "warn(['golden=' + (process.env.KARANDASH_UPDATE_GOLDEN !== undefined), " +
            "'baseline=' + (process.env.KARANDASH_UPDATE_BASELINE !== undefined), " +
            "'kenvGolden=' + (karandash.env('KARANDASH_UPDATE_GOLDEN') !== null), " +
            "'kenvBaseline=' + (karandash.env('KARANDASH_UPDATE_BASELINE') !== null)].join(' '))"

    @Test
    fun `declared env keeps only the declared vars in process env and karandash env`() =
        runTest(timeout = 2.minutes) {
            if (!nodeAvailable()) return@runTest

            val findings = runNode(probe, env = listOf("KARANDASH_UPDATE_GOLDEN"))

            assertEquals(
                listOf("golden=true baseline=false kenvGolden=true kenvBaseline=false"),
                findings.map { it.message },
            )
        }

    @Test
    fun `declared env strips undeclared vars from the initial environment and from child processes`() =
        runTest(timeout = 2.minutes) {
            if (!nodeAvailable()) return@runTest

            // Needles are built at runtime so the bridge script on node's own command line cannot match them.
            val findings =
                runNode(
                    "const has = (env, n) => new RegExp('(^|[\\\\0 \\\\n])' + n + '=').test(env);\n" +
                        "const baseline = 'KARANDASH_UPDATE_' + 'BASELINE'; const golden = 'KARANDASH_UPDATE_' + 'GOLDEN';\n" +
                        "const initial = process.platform === 'linux'\n" +
                        "  ? require('fs').readFileSync('/proc/self/environ', 'latin1')\n" +
                        "  : require('child_process').execFileSync('/bin/ps', " +
                        "['-E', '-ww', '-o', 'command=', '-p', String(process.pid)], { encoding: 'latin1' });\n" +
                        "const child = require('child_process').execFileSync('/usr/bin/env', { encoding: 'latin1' });\n" +
                        "warn(['initial', has(initial, baseline), has(initial, golden), " +
                        "'child', has(child, baseline), has(child, golden)].join(' '))",
                    env = listOf("KARANDASH_UPDATE_GOLDEN"),
                )

            assertEquals(listOf("initial false true child false true"), findings.map { it.message })
        }

    @Test
    fun `denylisted name in the declared env is still hidden`() =
        runTest(timeout = 2.minutes) {
            if (!nodeAvailable()) return@runTest

            val findings =
                runNode(
                    "warn('HOME=' + process.env.HOME + ' golden=' + (karandash.env('KARANDASH_UPDATE_GOLDEN') !== null))",
                    env = listOf("HOME", "KARANDASH_UPDATE_GOLDEN"),
                )

            assertEquals(listOf("HOME=undefined golden=true"), findings.map { it.message })
        }

    @Test
    fun `empty declared env leaves the script no env vars`() =
        runTest(timeout = 2.minutes) {
            if (!nodeAvailable()) return@runTest

            val findings = runNode("warn('count=' + Object.keys(process.env).length)", env = emptyList())

            assertEquals(listOf("count=0"), findings.map { it.message })
        }

    @Test
    fun `undeclared env reads through both channels warn once with names and no values`() =
        runTest(timeout = 2.minutes) {
            if (!nodeAvailable()) return@runTest
            val context = TestPluginContext()

            val findings = runNode(probe + "; process.env.HOME", context = context)

            assertEquals(
                listOf("golden=true baseline=true kenvGolden=true kenvBaseline=true"),
                findings.map { it.message },
            )
            assertEquals(
                listOf(
                    "Script 'check.mjs' read undeclared env vars [KARANDASH_UPDATE_BASELINE, KARANDASH_UPDATE_GOLDEN]; " +
                        "declare them in scripts[].env — undeclared reads will be stripped in the next release",
                ),
                context.warnings,
            )
        }

    @Test
    fun `enumerating the undeclared env counts as reading every var`() =
        runTest(timeout = 2.minutes) {
            if (!nodeAvailable()) return@runTest
            val context = TestPluginContext()

            runNode("Object.keys(process.env)", context = context)

            val warning = context.warnings.single()
            // The warning shows at most 50 names, so a large host environment is summarised as "and N more".
            assertTrue("read undeclared env vars [" in warning && (" more;" in warning || "KARANDASH_UPDATE_GOLDEN" in warning), warning)
        }

    @Test
    fun `undeclared script that reads no env vars does not warn`() =
        runTest(timeout = 2.minutes) {
            if (!nodeAvailable()) return@runTest
            val context = TestPluginContext()

            runNode("warn('quiet')", context = context)

            assertEquals(emptyList(), context.warnings)
        }
}
