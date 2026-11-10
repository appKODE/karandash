package io.karandash.cli.pipeline

import io.karandash.models.ChangeType
import io.karandash.models.DiffRefs
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.sdk.NoOpLogger
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkingCopyAtHeadTest {
    // relative to the test working directory, as the working copy is
    private val dir = "build/tmp/working-copy-${Random.nextLong().toULong()}"

    @AfterTest
    fun cleanUp() = TestFiles.deleteRecursively(Path(dir))

    private fun change(
        path: String,
        diff: String,
        type: ChangeType = ChangeType.MODIFIED,
    ) = FileChange(path = "$dir/$path", changeType = type, additions = 1, deletions = 0, diffContent = diff)

    private fun mr(vararg changes: FileChange) =
        MergeRequest(
            id = "1",
            iid = 7,
            title = "t",
            description = null,
            author = User(username = "dev", name = "Dev"),
            sourceBranch = "feature",
            targetBranch = "main",
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            changes = changes.toList(),
            commits = emptyList(),
            diffRefs = DiffRefs(baseSha = "base", headSha = "head", startSha = "base"),
        )

    private val edit = change("A.kt", "@@ -1 +1,2 @@\n fun a() = 1\n+fun b() = 2\r\n")

    @Test
    fun `a checkout holding every added line is at the head and serves the head from disk`() =
        runTest {
            TestFiles.writeText(Path("$dir/A.kt"), "fun a() = 1\r\nfun b() = 2\r\n")
            TestFiles.writeText(Path("$dir/Dep.kt"), "object Dep")
            val api = mutableListOf<String>()
            val reader = withWorkingCopy(mr(edit, change("Gone.kt", "-x\n", ChangeType.DELETED)), NoOpLogger, gitHead = null) { ref, path ->
                api += "$ref $path"
                "api"
            }

            assertEquals("object Dep", reader.read("head", "$dir/Dep.kt"))
            assertNull(reader.read("head", "$dir/Missing.kt"))
            assertEquals("api", reader.read("base", "$dir/Dep.kt"))
            assertEquals(listOf("base $dir/Dep.kt"), api)
        }

    @Test
    fun `a checkout missing or moving an added line or keeping a deleted file is not at the head`() {
        TestFiles.writeText(Path("$dir/A.kt"), "fun a() = 1\n")
        assertFalse(WorkingCopyAtHead.matches(mr(edit), gitHead = null))
        // the added line exists, but moved: the parent commit of a fix that moves a call
        TestFiles.writeText(Path("$dir/A.kt"), "fun b() = 2\nfun a() = 1\n")
        assertFalse(WorkingCopyAtHead.matches(mr(edit), gitHead = null))

        TestFiles.writeText(Path("$dir/A.kt"), "fun a() = 1\nfun b() = 2\n")
        TestFiles.writeText(Path("$dir/Gone.kt"), "x")
        assertFalse(WorkingCopyAtHead.matches(mr(edit, change("Gone.kt", "-x\n", ChangeType.DELETED)), gitHead = null))
        assertTrue(WorkingCopyAtHead.matches(mr(edit), gitHead = null))
    }

    @Test
    fun `a git checkout is at the head exactly when its HEAD is the head sha`() {
        assertTrue(WorkingCopyAtHead.matches(mr(edit), gitHead = "HEAD"))
        TestFiles.writeText(Path("$dir/A.kt"), "fun a() = 1\nfun b() = 2\n")
        assertFalse(WorkingCopyAtHead.matches(mr(edit), gitHead = "parent"))
    }

    @Test
    fun `a deletion-only MR proves nothing and paths outside the repository are never read`() {
        assertFalse(WorkingCopyAtHead.matches(mr(change("Gone.kt", "-x\n", ChangeType.DELETED)), gitHead = null))
        assertNull(WorkingCopyAtHead.read("/etc/hosts"))
        assertNull(WorkingCopyAtHead.read("../settings.gradle.kts"))
    }
}
