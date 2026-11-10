package io.karandash.cli.pipeline

import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.platform.GitRunner
import io.karandash.platform.ProcessResult
import io.karandash.sdk.NoOpLogger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalGitCommitsTest {
    private val base = "a".repeat(40)
    private val head = "b".repeat(40)

    private val mr =
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
            changes = emptyList(),
            commits = emptyList(),
            diffRefs = DiffRefs(baseSha = base, headSha = head, startSha = base),
        )

    /** A git holding only [base], with `CODEOWNERS` in it. */
    private val git =
        GitRunner { command, _, _ ->
            when (command.drop(1)) {
                listOf("cat-file", "-e", "--end-of-options", "$base^{commit}") -> ProcessResult(0, "", "")
                listOf("cat-file", "-s", "--end-of-options", "$base:CODEOWNERS") -> ProcessResult(0, "8\n", "")
                listOf("cat-file", "blob", "--end-of-options", "$base:CODEOWNERS") -> ProcessResult(0, "* @team\n", "")
                else -> ProcessResult(1, "", "missing")
            }
        }

    @Test
    fun `a commit in the local git is read from git objects and a miss never reaches the platform`() =
        runTest {
            val api = mutableListOf<String>()
            val reader = withLocalGitCommits(mr, NoOpLogger, gitHead = "c".repeat(40), git = git) { ref, path ->
                api += "$ref $path"
                "api"
            }

            assertEquals("* @team\n", reader.read(base, "CODEOWNERS"))
            assertNull(reader.read(base, "detekt.yml"))
            assertNull(reader.read(base, "../outside"))
            assertEquals("api", reader.read(head, "A.kt"))
            assertEquals("api", reader.read("main", "A.kt"))
            assertEquals(listOf("$head A.kt", "main A.kt"), api)
        }

    @Test
    fun `without a git checkout every read goes to the platform`() =
        runTest {
            val reader = withLocalGitCommits(mr, NoOpLogger, gitHead = null, git = git) { _, _ -> "api" }

            assertEquals("api", reader.read(base, "CODEOWNERS"))
        }
}
