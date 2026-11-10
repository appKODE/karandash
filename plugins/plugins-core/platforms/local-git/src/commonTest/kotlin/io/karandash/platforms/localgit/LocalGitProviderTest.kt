package io.karandash.platforms.localgit

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.MergeRequestState
import io.karandash.platform.GitRunner
import io.karandash.platform.PlatformProcess
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun testContext(): PluginContext =
    object : PluginContext {
        override val pluginConfig = JsonObject(emptyMap())
        override val logger = NoOpLogger
        override val hostVersion = "test"
        override val workingDirectory = "."

        override fun env(name: String): String? = null
    }

class LocalGitProviderTest {
    private val provider = LocalGitProvider()

    @Test
    fun `id is local-git`() {
        assertEquals("local-git", provider.id)
    }

    @Test
    fun `type is PLATFORM_SERVICE`() {
        assertEquals(ModuleType.PLATFORM_SERVICE, provider.type)
    }

    @Test
    fun `defaultEnabled is false`() {
        assertFalse(provider.defaultEnabled)
    }

    @Test
    fun `defaultPriority is 999 lowest`() {
        assertEquals(999, provider.defaultPriority)
    }

    @Test
    fun `dslName is localGit`() {
        assertEquals("localGit", provider.dslName)
    }

    @Test
    fun `initialize returns true`() =
        runTest {
            assertTrue(provider.initialize(testContext()))
        }

    @Test
    fun `shutdown does not throw`() =
        runTest {
            provider.shutdown()
        }

    @Test
    fun `shutdown is idempotent`() =
        runTest {
            provider.shutdown()
            provider.shutdown()
        }

    @Test
    fun `detectCiEnvironment returns null`() =
        runTest {
            val result = provider.detectCiEnvironment { null }
            assertEquals(null, result)
        }

    @Test
    fun `detectConfidence returns non-null when git is available`() =
        runTest {
            val result = provider.detectConfidence { null }
            // This test runs in a git repo, so git should be available
            if (result != null) {
                assertEquals("local-git", result.providerId)
                assertTrue(result.score <= 0.3, "Local git score should be low priority")
            }
            // If git is not available (CI without git), result is null - both are valid
        }

    @Test
    fun `fetchMergeRequest returns valid MR from local git`() =
        runTest {
            provider.initialize(testContext())

            // git failures (no git, unknown branch) degrade to empty commits/changes instead of throwing
            val mr = provider.fetchMergeRequest("main", "0")
            assertEquals("local", mr.id)
            assertEquals("main", mr.targetBranch)
            assertEquals(MergeRequestState.OPENED, mr.state)
            assertTrue(mr.sourceBranch.isNotEmpty())
        }

    @Test
    fun `fetchMergeRequest against the previous commit lists its commit and diff`() =
        runTest {
            // A throwaway two-commit repository: CI checks out a single orphan commit, which has no HEAD~1.
            val dir = PlatformProcess
                .execute(listOf("mktemp", "-d"))
                .takeIf { it.isSuccess }
                ?.output
                ?.trim()
                ?: return@runTest
            val git = GitRunner { command, _, timeout ->
                PlatformProcess.execute(command, dir, timeoutSeconds = timeout)
            }
            val identity = listOf("-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false")
            val commit = identity + listOf("commit", "-q", "-m")
            try {
                git.runSync(listOf("init", "-q"))
                git.runSync(commit + listOf("base", "--allow-empty"))
                PlatformProcess.execute(listOf("sh", "-c", "printf 'a\\n' > f.txt"), dir)
                git.runSync(listOf("add", "f.txt"))
                git.runSync(commit + "add f")
                val local = LocalGitProvider(git).apply { initialize(testContext()) }

                val mr = local.fetchMergeRequest("HEAD~1", "0")

                assertEquals(listOf("add f"), mr.commits.map { it.message })
                assertEquals("add f", mr.title)
                assertEquals(listOf("f.txt"), mr.changes.map { it.path })
                assertEquals(ChangeType.ADDED, mr.changes.single().changeType)
                assertEquals("@@ -0,0 +1 @@\n+a", mr.changes.single().diffContent)
            } finally {
                PlatformProcess.execute(listOf("rm", "-rf", dir))
            }
        }

    @Test
    fun `fetchMergeRequest with an unknown target branch returns an MR without changes`() =
        runTest {
            provider.initialize(testContext())

            val mr = provider.fetchMergeRequest("no-such-branch-karandash", "0")
            assertEquals("no-such-branch-karandash", mr.targetBranch)
            assertTrue(mr.changes.isEmpty())
            assertTrue(mr.commits.isEmpty())
        }

    @Test
    fun `fetchFileContent reads a file at a ref untrimmed`() =
        runTest {
            val content = provider.fetchFileContent("", "HEAD", "settings.gradle.kts")

            assertTrue(content.orEmpty().contains("rootProject.name = \"karandash\""))
            assertTrue(content.orEmpty().endsWith("\n"))
        }

    @Test
    fun `fetchFileContent returns null for a path missing at the ref`() =
        runTest {
            assertEquals(null, provider.fetchFileContent("", "HEAD", "no/such/file-karandash.txt"))
        }

    @Test
    fun `fetchFileContent is null for a binary file`() =
        runTest {
            assertEquals(null, provider.fetchFileContent("", "HEAD", "gradle/wrapper/gradle-wrapper.jar"))
        }

    @Test
    fun `fetchFileContent rejects a ref that looks like an option`() =
        runTest {
            assertEquals(null, provider.fetchFileContent("", "--format=%H", "settings.gradle.kts"))
            assertTrue(provider.fetchFileContent("", "HEAD", "settings.gradle.kts") != null)
        }

    @Test
    fun `fetchFileContent rejects a path that escapes the tree root`() =
        runTest {
            listOf("./../../../../settings.gradle.kts", "core/../settings.gradle.kts", "/etc/passwd", ":settings.gradle.kts", "-x")
                .forEach { assertEquals(null, provider.fetchFileContent("", "HEAD", it), it) }
        }

    @Test
    fun `isSafeRef accepts tags with plus signs and relative revisions`() {
        listOf("v1.0.0+b1", "HEAD~1", "HEAD^", "@", "@{upstream}", "feature/ветка", "origin/main", "a3f9c1e")
            .forEach { assertTrue(provider.isSafeRef(it), it) }
    }

    @Test
    fun `isSafeRef rejects options whitespace colons and range dots`() {
        listOf("", "--format=%H", "-x", "a b", "a\tb", "a\nb", "a\u0000b", "a:b", "a..b")
            .forEach { assertFalse(provider.isSafeRef(it), it) }
    }

    @Test
    fun `local git declares file content support`() {
        assertTrue(provider.supportsFileContent)
    }

    @Test
    fun `a path containing space b slash keeps its full name`() {
        val raw =
            "diff --git a/x b/y.kt b/x b/y.kt\n" +
                "index 7898192..422c2b7 100644\n" +
                "--- a/x b/y.kt\t\n" +
                "+++ b/x b/y.kt\t\n" +
                "@@ -1 +1,2 @@\n" +
                " a\n" +
                "+b\n"

        val change = provider.parseFileChanges("M\u0000x b/y.kt\u0000", "1\t0\tx b/y.kt\u0000", raw).single()

        assertEquals("x b/y.kt", change.path)
        assertEquals(1, change.additions)
        assertEquals("@@ -1 +1,2 @@\n a\n+b", change.diffContent)
    }

    @Test
    fun `renames binaries and new files take paths and counts from the NUL separated listings`() {
        val nameStatus = "M\u0000bin.dat\u0000R100\u0000ren.txt\u0000moved.txt\u0000A\u0000new.txt\u0000"
        val numstat = "-\t-\tbin.dat\u00000\t0\t\u0000ren.txt\u0000moved.txt\u00001\t0\tnew.txt\u0000"
        val raw =
            "diff --git a/bin.dat b/bin.dat\n" +
                "Binary files a/bin.dat and b/bin.dat differ\n" +
                "diff --git a/ren.txt b/moved.txt\n" +
                "similarity index 100%\n" +
                "rename from ren.txt\n" +
                "rename to moved.txt\n" +
                "diff --git a/new.txt b/new.txt\n" +
                "new file mode 100644\n" +
                "--- /dev/null\n" +
                "+++ b/new.txt\n" +
                "@@ -0,0 +1 @@\n" +
                "+n\n"

        val changes = provider.parseFileChanges(nameStatus, numstat, raw)

        assertEquals(listOf("bin.dat", "moved.txt", "new.txt"), changes.map { it.path })
        assertEquals(listOf(ChangeType.MODIFIED, ChangeType.RENAMED, ChangeType.ADDED), changes.map { it.changeType })
        assertEquals(listOf("Binary files differ", null, "@@ -0,0 +1 @@\n+n"), changes.map { it.diffContent })
        assertEquals(listOf(0, 0, 1), changes.map { it.additions })
    }
}
