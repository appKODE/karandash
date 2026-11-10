package io.karandash.plugins.ai.dispatch

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.logger.NoOpLogger
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptDumpTest {
    private fun tempDir() = Path(TestFiles.createTestTempDirectory("karandash-prompt-dump"), "dump")

    private fun bytes(path: Path) = SystemFileSystem.source(path).buffered().use { it.readByteArray() }

    @Test
    fun `each call is written per rule byte for byte`() =
        runTest {
            val dir = tempDir()
            val dump = PromptDump(dir.toString(), "ai-review", NoOpLogger)
            val first =
                AiCompletionRequest(prompt = "diff ü\r\n+line", systemPrompt = "system ✓", model = "m", contextBlocks = listOf("ctx"))
            val second = AiCompletionRequest(prompt = "second", model = "m")

            dump.write(first)
            dump.write(second)

            assertContentEquals(first.prompt.encodeToByteArray(), bytes(Path(dir, "ai-review-1.prompt.txt")))
            assertContentEquals(checkNotNull(first.systemPrompt).encodeToByteArray(), bytes(Path(dir, "ai-review-1.system.txt")))
            assertContentEquals("ctx".encodeToByteArray(), bytes(Path(dir, "ai-review-1.context-1.txt")))
            assertContentEquals("second".encodeToByteArray(), bytes(Path(dir, "ai-review-2.prompt.txt")))
            assertFalse(SystemFileSystem.exists(Path(dir, "ai-review-2.system.txt")), "no system prompt, no file")
        }

    @Test
    fun `a rule id cannot write outside the dump directory`() =
        runTest {
            val dir = tempDir()

            PromptDump(dir.toString(), "../escape", NoOpLogger).write(AiCompletionRequest(prompt = "p", model = "m"))

            assertContentEquals("p".encodeToByteArray(), bytes(Path(dir, "___escape-1.prompt.txt")))
        }

    @Test
    fun `a new dump directory ignores itself in git`() =
        runTest {
            val dir = tempDir()
            PromptDump(dir.toString(), "a", NoOpLogger).write(AiCompletionRequest(prompt = "p", model = "m"))
            assertContentEquals("*\n".encodeToByteArray(), bytes(Path(dir, ".gitignore")))
        }

    @Test
    fun `a directory that already existed gets no gitignore`() =
        runTest {
            val dir = tempDir()
            SystemFileSystem.createDirectories(dir)
            PromptDump(dir.toString(), "a", NoOpLogger).write(AiCompletionRequest(prompt = "p", model = "m"))
            assertFalse(SystemFileSystem.exists(Path(dir, ".gitignore")), "a gitignore written into an existing directory")
            assertTrue(SystemFileSystem.exists(Path(dir, "a-1.prompt.txt")))
        }

    @Test
    fun `an existing gitignore is kept`() =
        runTest {
            val own = tempDir()
            SystemFileSystem.createDirectories(own)
            SystemFileSystem.sink(Path(own, ".gitignore")).buffered().use { it.writeString("mine\n") }
            PromptDump(own.toString(), "a", NoOpLogger).write(AiCompletionRequest(prompt = "p", model = "m"))
            assertContentEquals("mine\n".encodeToByteArray(), bytes(Path(own, ".gitignore")))
        }
}
