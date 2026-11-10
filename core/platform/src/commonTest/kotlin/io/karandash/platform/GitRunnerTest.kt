package io.karandash.platform

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class GitRunnerTest {
    private val calls = mutableListOf<List<String>>()
    private val timeouts = mutableListOf<Int>()

    private fun runner(
        size: Long,
        content: String = "text",
    ) = GitRunner { command, _, timeoutSeconds ->
        calls += command
        timeouts += timeoutSeconds
        when (command[2]) {
            "-s" -> ProcessResult(0, "$size\n", "")
            "blob" -> ProcessResult(0, content, "")
            else -> ProcessResult(1, "", "unexpected")
        }
    }

    @Test
    fun `a blob over the cap is never read`() =
        runTest {
            val git = runner(size = 101)

            assertEquals(GitRunner.Blob.TooLarge(101), git.readBlob("HEAD", "big.bin", maxBytes = 100))
            assertEquals(listOf(listOf("git", "cat-file", "-s", "--end-of-options", "HEAD:big.bin")), calls)
        }

    @Test
    fun `a NUL in the first 8 KB makes the blob binary`() =
        runTest {
            val git = runner(size = 10, content = "PK\u0000\u0003")

            assertIs<GitRunner.Blob.Binary>(git.readBlob("HEAD", "a.png", maxBytes = 100))
        }

    @Test
    fun `a NUL past the sniff window keeps the blob as text`() =
        runTest {
            val content = "a".repeat(GitRunner.BINARY_SNIFF_CHARS) + "\u0000"
            val git = runner(size = content.length.toLong(), content = content)

            assertEquals(content, git.readBlob("HEAD", "a.txt", maxBytes = Int.MAX_VALUE).textOrNull)
        }

    @Test
    fun `every object spec follows end-of-options and the default timeout is applied`() =
        runTest {
            runner(size = 4).readBlob("abc", "-weird", maxBytes = 100)

            assertEquals(2, calls.size)
            calls.forEach { assertEquals("--end-of-options", it[it.size - 2]) }
            assertEquals(listOf(ProcessTimeouts.DEFAULT_SECONDS, ProcessTimeouts.DEFAULT_SECONDS), timeouts)
        }

    @Test
    fun `an explicit timeout reaches the process and a failing git is null`() =
        runTest {
            val git = GitRunner { _, _, timeoutSeconds ->
                timeouts += timeoutSeconds
                ProcessResult(ProcessTimeouts.EXIT_CODE, "", "timed out")
            }

            assertNull(git.git(listOf("log"), timeoutSeconds = 5))
            assertEquals(listOf(5), timeouts)
        }

    @Test
    fun `git that cannot start is null not an exception`() =
        runTest {
            val git = GitRunner { _, _, _ -> throw IllegalStateException("no git") }

            assertNull(git.git(listOf("status")))
            assertIs<GitRunner.Blob.Failed>(git.readBlob("HEAD", "a", maxBytes = 1))
        }

    @Test
    fun `a failing size probe a bad size and a failing read are Failed with the git exit`() =
        runTest {
            val missing = GitRunner { _, _, _ -> ProcessResult(128, "", "fatal: bad object\n") }
            assertEquals(GitRunner.Blob.Failed(128, "fatal: bad object"), missing.readBlob("HEAD", "a", maxBytes = 10))

            val garbled = GitRunner { _, _, _ -> ProcessResult(0, "lots\n", "") }
            assertEquals(GitRunner.Blob.Failed(0, "unparsable blob size"), garbled.readBlob("HEAD", "a", maxBytes = 10))

            val unreadable = GitRunner { command, _, _ ->
                if (command[2] == "-s") ProcessResult(0, "3", "") else ProcessResult(1, "", "read error ")
            }
            assertEquals(GitRunner.Blob.Failed(1, "read error"), unreadable.readBlob("HEAD", "a", maxBytes = 10))
            assertNull(unreadable.readBlob("HEAD", "a", maxBytes = 10).textOrNull)
        }

    @Test
    fun `gitSync returns stdout untrimmed on success`() {
        val git = GitRunner { command, workingDir, _ -> ProcessResult(0, "${command.drop(1)} in $workingDir\n", "") }

        assertEquals("[rev-parse, HEAD] in /repo\n", git.gitSync(listOf("rev-parse", "HEAD"), workingDir = "/repo"))
        assertEquals("[status] in null\n", git.gitSync(listOf("status")))
        assertEquals(0, git.runSync(listOf("status"))?.exitCode)
    }

    @Test
    fun `git that stops starting between the size probe and the read is Failed`() =
        runTest {
            val git = GitRunner { command, _, _ -> if (command[2] == "-s") ProcessResult(0, "3", "") else error("gone") }

            assertEquals(GitRunner.Blob.Failed(-1, "git could not be run"), git.readBlob("HEAD", "a", maxBytes = 10))
        }

    @Test
    fun `ceilSeconds rounds a partial second up`() {
        assertEquals(1, ProcessTimeouts.ceilSeconds(1.milliseconds))
        assertEquals(300, ProcessTimeouts.ceilSeconds(5.minutes))
        assertTrue(ProcessTimeouts.ceilSeconds(0.milliseconds) == 0)
    }
}
