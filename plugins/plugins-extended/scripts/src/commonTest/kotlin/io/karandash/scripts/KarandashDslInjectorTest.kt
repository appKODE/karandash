package io.karandash.scripts

import io.karandash.duration.KarandashDuration
import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class KarandashDslInjectorTest {
    @Test
    fun stripDangerImportsRemovesDoubleQuote() {
        val source = """
            import { danger, warn } from "danger"
            const mr = karandash.mr;
        """.trimIndent()
        val result = KarandashDslInjector.stripDangerImports(source)
        assertFalse(result.contains("from \"danger\""))
        assertTrue(result.contains("const mr = karandash.mr;"))
    }

    @Test
    fun stripDangerImportsRemovesSingleQuote() {
        val source = """
            import { danger } from 'danger'
            karandash.fail("test");
        """.trimIndent()
        val result = KarandashDslInjector.stripDangerImports(source)
        assertFalse(result.contains("from 'danger'"))
        assertTrue(result.contains("karandash.fail"))
    }

    @Test
    fun stripDangerImportsPreservesOtherImports() {
        val source = """
            import { readFile } from "fs"
            import { danger } from "danger"
            const x = 1;
        """.trimIndent()
        val result = KarandashDslInjector.stripDangerImports(source)
        assertTrue(result.contains("import { readFile } from \"fs\""))
        assertFalse(result.contains("from \"danger\""))
    }

    @Test
    fun stripDangerImportsHandlesNoImports() {
        val source = "const x = 1;\nkarandash.warn('hello');"
        val result = KarandashDslInjector.stripDangerImports(source)
        assertEquals(source, result)
    }

    private val mr = createTestMergeRequest(
        changes = listOf(
            FileChange(path = "src/a.kt", changeType = ChangeType.MODIFIED, additions = 3, deletions = 1),
            FileChange(path = "src/b.kt", changeType = ChangeType.ADDED, additions = 10),
        ),
    )

    private suspend fun runScript(
        source: String,
        timeoutMs: Long = 5_000,
        env: List<String>? = null,
        mr: MergeRequest = this.mr,
    ): Pair<List<String>, TestPluginContext> {
        val context = TestPluginContext(envVars = hostEnv)
        val entry = ScriptEntryConfig(path = "inline.js", timeout = KarandashDuration.ofMillis(timeoutMs), env = env)
        // Real time: the sandbox timeout must hold against a script that never yields.
        val findings = withContext(Dispatchers.Default) { SandboxScriptExecutor(context).run(entry, mr, source) }
        return findings.map { it.message } to context
    }

    @Test
    fun mrExposesChangedFilesAdditionsAndDeletions() =
        runTest {
            val (messages, _) = runScript(
                "karandash.warn([karandash.mr.changedFiles.length, karandash.mr.changedFiles.join(','), " +
                    "karandash.mr.additions, karandash.mr.deletions].join('|'));",
            )
            assertEquals(listOf("2|src/a.kt,src/b.kt|13|1"), messages)
        }

    /** `danger.git` was an empty object, so Dangerfiles reading it saw no files or commits. */
    @Test
    fun `danger git lists modified created and deleted files and commits`() =
        runTest {
            val gitMr = createTestMergeRequest(
                changes = listOf(
                    FileChange(path = "src/a.kt", changeType = ChangeType.MODIFIED),
                    FileChange(path = "src/new.kt", changeType = ChangeType.ADDED),
                    FileChange(path = "src/gone.kt", changeType = ChangeType.DELETED),
                    FileChange(path = "src/moved.kt", changeType = ChangeType.RENAMED, oldPath = "src/old.kt"),
                ),
                commits = listOf(
                    Commit("abc123", "feat: one", CommitAuthor("Ann", "ann@example.com"), "2024-03-15T10:30:00Z"),
                ),
            )
            val (messages, _) = runScript(
                "var g = danger.git; var c = g.commits[0];" +
                    "warn([g.modified_files.join(','), g.created_files.join(','), g.deleted_files.join(','), " +
                    "c.sha, c.message, c.author.name, c.author.email, c.author.date].join('|'));",
                mr = gitMr,
            )
            assertEquals(
                listOf("src/a.kt,src/moved.kt|src/new.kt|src/gone.kt|abc123|feat: one|Ann|ann@example.com|2024-03-15T10:30:00Z"),
                messages,
            )
        }

    @Test
    fun endlessScriptEndsAtTheTimeout() =
        runTest(timeout = 60.seconds) {
            val started = TimeSource.Monotonic.markNow()
            val (messages, context) = runScript("while (true) {}", timeoutMs = 300)
            assertTrue(started.elapsedNow() < 10.seconds, "sandbox ran for ${started.elapsedNow()}")
            assertTrue(messages.isEmpty())
            assertTrue(context.warnings.any { "timed out" in it }, "no timeout warning: ${context.warnings}")
        }

    private val hostEnv = mapOf(
        "JIRA_TOKEN" to "jira-secret",
        "CI_JOB_TOKEN" to "job-secret",
        "HOME" to "/home/ci",
        "Path" to "/p",
        "Home" to "/h"
    )

    private val envProbe =
        "karandash.warn(['JIRA_TOKEN', 'CI_JOB_TOKEN', 'HOME'].map(function(n) { return n + '=' + karandash.env(n); }).join(' '));"

    @Test
    fun `declared env exposes only the declared vars to karandash env`() =
        runTest {
            val (messages, _) = runScript(envProbe, env = listOf("JIRA_TOKEN"))
            assertEquals(listOf("JIRA_TOKEN=jira-secret CI_JOB_TOKEN=null HOME=null"), messages)
        }

    @Test
    fun `denylisted name in the declared env is still denied`() =
        runTest {
            val (messages, _) = runScript(envProbe, env = listOf("HOME", "CI_JOB_TOKEN"))
            assertEquals(listOf("JIRA_TOKEN=null CI_JOB_TOKEN=job-secret HOME=null"), messages)
        }

    @Test
    fun `empty declared env exposes nothing`() =
        runTest {
            val (messages, context) = runScript(envProbe, env = emptyList())
            assertEquals(listOf("JIRA_TOKEN=null CI_JOB_TOKEN=null HOME=null"), messages)
            assertEquals(emptyList(), context.warnings)
        }

    @Test
    fun `undeclared env reads warn once with names and no values`() =
        runTest {
            val (messages, context) = runScript(envProbe + "karandash.env('JIRA_TOKEN');")
            assertEquals(listOf("JIRA_TOKEN=jira-secret CI_JOB_TOKEN=job-secret HOME=null"), messages)
            assertEquals(
                listOf(
                    "Script 'inline.js' read undeclared env vars [CI_JOB_TOKEN, JIRA_TOKEN]; declare them in " +
                        "scripts[].env — undeclared reads will be stripped in the next release",
                ),
                context.warnings,
            )
        }

    @Test
    fun `undeclared script that reads no env vars does not warn`() =
        runTest {
            val (_, context) = runScript("karandash.warn('quiet');")
            assertEquals(emptyList(), context.warnings)
        }

    @Test
    fun `undeclared env warning drops malformed names and keeps valid ones`() =
        runTest {
            val (_, context) = runScript(
                "karandash.env('BAD\\u001b[31mX'); karandash.env('A\\nB'); karandash.env(''); " +
                    "karandash.env('A'.repeat(129)); karandash.env('JIRA_TOKEN');",
            )
            val warning = context.warnings.single()
            assertTrue("[JIRA_TOKEN]" in warning, warning)
            assertTrue(warning.none { it == '\u001b' || it == '\n' }, warning)
        }

    @Test
    fun `undeclared env warning is capped at fifty names`() =
        runTest {
            val (_, context) = runScript("for (var i = 0; i < 1000; i++) karandash.env('VAR_' + i);")
            val warning = context.warnings.single()
            assertTrue("and 950 more" in warning, warning)
            assertTrue(warning.length < 1_500, warning.length.toString())
        }

    @Test
    fun `denylist wins over declared env regardless of case`() =
        runTest {
            val (messages, _) = runScript(
                "karandash.warn(['Path', 'Home', 'JIRA_TOKEN'].map(function(n) { return n + '=' + karandash.env(n); }).join(' '));",
                env = listOf("Path", "Home", "JIRA_TOKEN"),
            )
            assertEquals(listOf("Path=null Home=null JIRA_TOKEN=jira-secret"), messages)
        }

    @Test
    fun `undeclared env hides denylisted names in any case and does not warn about them`() =
        runTest {
            val (messages, context) = runScript("karandash.warn(String(karandash.env('Home'))); karandash.env('JIRA_TOKEN');")
            assertEquals(listOf("null"), messages)
            assertTrue("[JIRA_TOKEN]" in context.warnings.single(), context.warnings.toString())
        }
}
