package io.karandash.content

import io.karandash.api.exception.ContentResolveException
import io.karandash.api.module.PlatformServiceModule
import io.karandash.platform.GitRunner
import io.karandash.platform.PlatformProcess
import io.karandash.platform.ProcessResult
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

class GitRepoFileFetcherTest {
    private fun gitRepo(): Path {
        val dir = TestFiles.createTestTempDirectory("git-repo-fetcher")
        SystemFileSystem.createDirectories(dir)
        SystemFileSystem.sink(Path(dir, "README.md")).buffered().use { it.writeString("committed") }
        val git = listOf("git", "-c", "user.email=t@t", "-c", "user.name=t", "-c", "commit.gpgsign=false")
        PlatformProcess.execute(listOf("git", "init", "-q"), workingDir = dir.toString())
        PlatformProcess.execute(listOf("git", "add", "README.md"), workingDir = dir.toString())
        PlatformProcess.execute(git + listOf("commit", "-q", "-m", "init"), workingDir = dir.toString())
        return dir
    }

    @Test
    fun readsCommittedFileAtHead() =
        runTest {
            val dir = gitRepo()
            val result = GitRepoFileFetcher(dir.toString()).fetch("README.md", null)
            assertEquals("committed", result.getOrThrow().trimEnd())
        }

    @Test
    fun missingFileIsTypedFailure() =
        runTest {
            val dir = gitRepo()
            val error = GitRepoFileFetcher(dir.toString()).fetch("nope.md", null).exceptionOrNull()
            assertIs<ContentResolveException>(error)
            assertEquals("nope.md", error.reference)
        }

    @Test
    fun pathTraversalIsRejectedWithoutRunningGit() =
        runTest {
            val error = GitRepoFileFetcher(".").fetch("../secret", null).exceptionOrNull()
            assertIs<ContentResolveException>(error)
            assertTrue(error.message?.contains("Invalid repo file path") == true)
        }

    /** an option-like path never reaches `git show`. */
    @Test
    fun `option-like path is rejected without running git`() =
        runTest {
            val error = GitRepoFileFetcher(".").fetch("--output=/tmp/x", null).exceptionOrNull()
            assertIs<ContentResolveException>(error)
            assertTrue(error.message?.contains("must not start with '-'") == true)
        }

    @Test
    fun optionLikeRefIsRejected() =
        runTest {
            val error = GitRepoFileFetcher(".").fetch("README.md", "--output=/tmp/x").exceptionOrNull()
            assertIs<ContentResolveException>(error)
            assertTrue(error.message?.contains("Invalid git ref") == true)
        }

    @Test
    fun `a file over the content cap is a failure and its blob is never read`() =
        runTest {
            val commands = mutableListOf<List<String>>()
            val git = GitRunner { command, _, _ ->
                commands += command
                ProcessResult(0, "${PlatformServiceModule.MAX_FILE_CONTENT_BYTES + 1}", "")
            }

            val error = GitRepoFileFetcher(".", git).fetch("big.json", null).exceptionOrNull()

            assertIs<ContentResolveException>(error)
            assertTrue(error.message?.contains("cap") == true)
            assertEquals(listOf("-s"), commands.map { it[2] })
        }
}
