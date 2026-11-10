package io.karandash.loader.bridge

import io.karandash.api.exception.ContentResolveException
import io.karandash.sdk.content.ContentSource
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GuestContentPolicyTest {
    private fun tempDir(name: String): File = File(TestFiles.createTestTempDirectory(name).toString()).canonicalFile

    @Test
    fun `local files resolve from the config dir and the git checkout above it`() =
        runTest {
            val repo = tempDir("guest-content-git")
            File(repo, ".git").mkdirs()
            File(repo, "shared.md").writeText("shared")
            val configDir = File(repo, ".karandash").apply { mkdirs() }
            File(configDir, "prompt.md").writeText("prompt")
            val policy = GuestContentPolicy(configDir.path)

            assertEquals("prompt", policy.resolve(ContentSource.LocalFile("prompt.md")))
            assertEquals("shared", policy.resolve(ContentSource.LocalFile("../shared.md")))
        }

    @Test
    fun `symlink escapes absolute paths and oversized files are refused`() =
        runTest {
            val configDir = tempDir("guest-content-root")
            val outside = File(tempDir("guest-content-outside"), "secret.md").apply { writeText("secret") }
            Files.createSymbolicLink(File(configDir, "link.md").toPath(), outside.toPath())
            File(configDir, "big.md").writeBytes(ByteArray((GuestContentPolicy.MAX_FILE_BYTES + 1).toInt()))
            val policy = GuestContentPolicy(configDir.path)

            assertFailsWith<ContentResolveException> { policy.resolve(ContentSource.LocalFile("link.md")) }
            assertFailsWith<ContentResolveException> { policy.resolve(ContentSource.LocalFile(outside.path)) }
            assertFailsWith<ContentResolveException> { policy.resolve(ContentSource.LocalFile("big.md")) }
            assertFailsWith<ContentResolveException> { policy.resolve(ContentSource.RemoteFile("https://example.com/p")) }
        }

    @Test
    fun `repo files go through the host resolver when the transport has one`() =
        runTest {
            val configDir = tempDir("guest-content-repo")
            val repoFile = ContentSource.RepoFile("docs/a.md")
            assertFailsWith<ContentResolveException> { GuestContentPolicy(configDir.path).resolve(repoFile) }
            assertEquals("from repo", GuestContentPolicy(configDir.path) { "from repo" }.resolve(repoFile))
        }
}
