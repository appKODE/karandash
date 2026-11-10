package io.karandash.providers.lifecycle

import io.karandash.api.module.PlatformServiceModule
import io.karandash.models.DiffRefs
import io.karandash.models.finding.Finding
import io.karandash.plugins.outputs.PlatformOutputModule
import io.karandash.providers.lifecycle.LifecycleHarness.Companion.finding
import io.karandash.providers.lifecycle.LifecycleHarness.Companion.report
import io.karandash.testfixtures.TestPluginContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What one MR run costs its readers, per platform over a stateful MockEngine fake: a second identical run makes no
 * write call, N new findings are one publish call, and a fixed finding gets no new note.
 */
class NotificationContractTest {
    // ── GitLab ──

    @Test
    fun `GitLab a second identical run makes no write call`() =
        runTest {
            val gitlab = FakeGitLab()
            gitlab.push("sha1", files, findings)
            val before = gitlab.mutations.size
            gitlab.push("sha1", files, findings)
            assertEquals(emptyList(), gitlab.mutations.drop(before))
        }

    @Test
    fun `GitLab N new findings are one bulk publish`() =
        runTest {
            val gitlab = FakeGitLab()
            gitlab.push("sha1", files, findings)
            assertEquals(1, gitlab.bulkPublishes)
            assertEquals(findings.size, gitlab.posts.size)
            assertTrue(gitlab.mutations.none { it.endsWith("/discussions") }, gitlab.mutations.toString())
        }

    @Test
    fun `GitLab without draft notes posts each comment after one warning`() =
        runTest {
            val gitlab = FakeGitLab().apply { draftNotes = false }
            gitlab.push("sha1", files, findings)
            assertEquals(0, gitlab.bulkPublishes)
            assertEquals(findings.size, gitlab.posts.size)
        }

    @Test
    fun `GitLab publish each posts one discussion per finding`() =
        runTest {
            val gitlab = FakeGitLab()
            gitlab.push("sha1", files, findings, publish = "each")
            assertEquals(0, gitlab.bulkPublishes)
            assertEquals(findings.size, gitlab.mutations.count { it == "POST /merge_requests/7/discussions" }, gitlab.mutations.toString())
        }

    @Test
    fun `GitLab a fixed finding gets no new note`() =
        runTest {
            val gitlab = FakeGitLab()
            gitlab.push("sha1", files, findings)
            val posts = gitlab.posts.size
            val before = gitlab.mutations.size
            gitlab.push("sha2", fixed, findings.drop(1))
            val second = gitlab.mutations.drop(before)
            assertEquals(posts, gitlab.posts.size)
            assertEquals(emptyList(), gitlab.replies)
            assertEquals(1, gitlab.resolves.size)
            assertTrue(second.none { it.startsWith("POST") }, second.toString())
        }

    // ── GitHub ──

    @Test
    fun `GitHub a second identical run makes no write call`() =
        runTest {
            val github = FakeGitHub()
            github.push("sha1", files, findings)
            val before = github.mutations.size
            github.push("sha1", files, findings)
            assertEquals(emptyList(), github.mutations.drop(before))
        }

    @Test
    fun `GitHub N new findings are one review`() =
        runTest {
            val github = FakeGitHub()
            github.push("sha1", files, findings)
            assertEquals(1, github.reviews)
            assertEquals(findings.size, github.reviewComments.size)
        }

    @Test
    fun `GitHub a fixed finding gets no new note`() =
        runTest {
            val github = FakeGitHub()
            github.push("sha1", files, findings)
            github.push("sha2", fixed, findings.drop(1))
            assertEquals(1, github.reviews)
            assertTrue(github.mutations.none { "addPullRequestReviewThreadReply" in it }, github.mutations.toString())
            assertEquals(1, github.mutations.count { "resolveReviewThread" in it }, github.mutations.toString())
        }

    // ── Gerrit ──

    @Test
    fun `Gerrit a second identical run makes no write call`() =
        runTest {
            val gerrit = FakeGerrit()
            gerrit.push("sha1", findings)
            val before = gerrit.mutations.size
            gerrit.push("sha1", findings)
            assertEquals(emptyList(), gerrit.mutations.drop(before))
        }

    @Test
    fun `Gerrit N new findings and the summary are one review`() =
        runTest {
            val gerrit = FakeGerrit()
            gerrit.push("sha1", findings)
            assertEquals(1, gerrit.mutations.size, gerrit.mutations.toString())
            assertEquals(findings.size, gerrit.inlinePosts.size)
        }

    @Test
    fun `Gerrit a fixed finding gets no new inline comment`() =
        runTest {
            val gerrit = FakeGerrit()
            gerrit.push("sha1", findings)
            gerrit.push("sha2", findings.drop(1))
            assertEquals(findings.size, gerrit.inlinePosts.size)
        }

    private suspend fun FakeGitLab.push(
        sha: String,
        files: Map<String, String>,
        findings: List<Finding>,
        publish: String? = null,
    ) {
        setHead(files)
        run(service(), "42", sha, findings, publish)
    }

    private suspend fun FakeGitHub.push(
        sha: String,
        files: Map<String, String>,
        findings: List<Finding>,
    ) {
        setHead(files)
        run(service(), "o/r", sha, findings)
    }

    private suspend fun FakeGerrit.push(
        sha: String,
        findings: List<Finding>,
    ) = run(service(), "demo", sha, findings)

    /** One CI run as the pipeline makes it: the platform output with this run's notification state. */
    private suspend fun run(
        platform: PlatformServiceModule,
        project: String,
        sha: String,
        findings: List<Finding>,
        publish: String? = null,
    ) {
        val config = buildJsonObject {
            publish?.let { putJsonObject("inline") { put("publish", it) } }
            putJsonObject("notificationState") {
                put("passed", findings.isEmpty())
                // Any hex stands in for the fingerprint hash: these runs differ only in how many findings they have
                put("findings", "f${findings.size}")
            }
        }
        val module = PlatformOutputModule()
        module.initialize(TestPluginContext(pluginConfig = config))
        module.configurePlatformService(platform, project, "7", DiffRefs(baseSha = "base", headSha = sha, startSha = "base"))
        module.output(report(findings), config)
    }

    private val code = "fun a(x: Int?) {\n    println(x!!)\n    val y = x as String\n    check(x > 0)\n}\n"
    private val files = mapOf("a.kt" to code)
    private val fixed = mapOf("a.kt" to code.replace("println(x!!)", "println(x ?: 0)"))
    private val findings =
        listOf(
            finding("null-safety", "a.kt", 2, "println(x!!)", "Avoid `!!` on x"),
            finding("unsafe-cast", "a.kt", 3, "val y = x as String", "Unsafe cast"),
            finding("precondition", "a.kt", 4, "check(x > 0)", "Prefer require"),
        )
}
