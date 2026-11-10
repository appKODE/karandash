package io.karandash.content

import io.karandash.api.exception.ContentResolveException
import io.karandash.config.AuthConfig
import io.karandash.config.AuthType
import io.karandash.sdk.content.ContentSource
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContentResolverTest {
    // resolved: the confinement checks compare absolute paths, and the CI image's temp dir comes back relative
    private fun tempDir(label: String): Path = TestFiles.createTestTempDirectory("content-resolver-$label")

    private fun writeFile(
        path: Path,
        content: String
    ) {
        SystemFileSystem.sink(path).buffered().use { it.writeString(content) }
    }

    private fun resolver(
        configDir: String,
        auth: AuthRegistry = AuthRegistry.EMPTY,
        fetcher: RemoteContentFetcher? = null,
    ) = ContentResolver(configDir = configDir, authRegistry = auth, fetcher = fetcher)

    // -------------------------------------------------------------------------
    // Inline
    // -------------------------------------------------------------------------

    @Test
    fun inlineResolutionReturnsValueDirectly() =
        runTest {
            val dir = tempDir("inline")
            try {
                val result = resolver(dir.toString()).resolve(ContentSource.Inline("hello world"))
                assertEquals("hello world", result)
            } finally {
                SystemFileSystem.delete(dir)
            }
        }

    // -------------------------------------------------------------------------
    // LocalFile
    // -------------------------------------------------------------------------

    @Test
    fun localFileResolutionReadsFileContent() =
        runTest {
            val dir = tempDir("local-ok")
            val file = Path(dir, "content.txt")
            try {
                writeFile(file, "file content here")
                val result = resolver(dir.toString()).resolve(ContentSource.LocalFile("content.txt"))
                assertEquals("file content here", result)
            } finally {
                SystemFileSystem.delete(file)
                SystemFileSystem.delete(dir)
            }
        }

    @Test
    fun localFileMissingThrowsContentResolveException() =
        runTest {
            val dir = tempDir("local-missing")
            try {
                SystemFileSystem.createDirectories(dir)
                val ex = assertFailsWith<ContentResolveException> {
                    resolver(dir.toString()).resolve(ContentSource.LocalFile("does-not-exist.txt"))
                }
                assertEquals("LocalFile", ex.sourceType)
                assertTrue(ex.message?.contains("does-not-exist.txt") == true)
            } finally {
                SystemFileSystem.delete(dir)
            }
        }

    @Test
    fun localFileTooLargeThrowsContentResolveException() =
        runTest {
            val dir = tempDir("local-large")
            val file = Path(dir, "big.txt")
            try {
                // Write MAX_FILE_SIZE_BYTES + 1 bytes
                val oversize = ByteArray(ContentResolver.MAX_FILE_SIZE_BYTES + 1) { 'A'.code.toByte() }
                SystemFileSystem.sink(file).buffered().use { it.write(oversize) }

                val ex = assertFailsWith<ContentResolveException> {
                    resolver(dir.toString()).resolve(ContentSource.LocalFile("big.txt"))
                }
                assertEquals("LocalFile", ex.sourceType)
                assertTrue(ex.message?.contains("exceeds maximum size") == true)
            } finally {
                SystemFileSystem.delete(file)
                SystemFileSystem.delete(dir)
            }
        }

    // -------------------------------------------------------------------------
    // RemoteFile
    // -------------------------------------------------------------------------

    @Test
    fun remoteFileWithNoFetcherThrowsContentResolveException() =
        runTest {
            val dir = tempDir("remote-no-fetcher")
            try {
                val ex = assertFailsWith<ContentResolveException> {
                    resolver(dir.toString()).resolve(ContentSource.RemoteFile("https://example.com/file.txt"))
                }
                assertEquals("RemoteFile", ex.sourceType)
                assertTrue(ex.message?.contains("RemoteContentFetcher") == true)
            } finally {
                SystemFileSystem.delete(dir)
            }
        }

    @Test
    fun remoteFileWithFetcherCallsFetcherAndReturnsContent() =
        runTest {
            val dir = tempDir("remote-ok")
            try {
                val fakeFetcher = RemoteContentFetcher { url, _ ->
                    if (url == "https://example.com/remote.txt") "remote content" else error("unexpected url")
                }
                val result = resolver(dir.toString(), fetcher = fakeFetcher)
                    .resolve(ContentSource.RemoteFile("https://example.com/remote.txt"))
                assertEquals("remote content", result)
            } finally {
                SystemFileSystem.delete(dir)
            }
        }

    @Test
    fun remoteFilePassesAuthConfigToFetcher() =
        runTest {
            val dir = tempDir("remote-auth")
            val authCfg = AuthConfig(type = AuthType.TOKEN, token = "secret")
            val authRegistry = AuthRegistry(mapOf("my-auth" to authCfg))
            var capturedAuth: AuthConfig? = null
            val fakeFetcher = RemoteContentFetcher { _, auth ->
                capturedAuth = auth
                "content"
            }
            try {
                resolver(dir.toString(), auth = authRegistry, fetcher = fakeFetcher)
                    .resolve(ContentSource.RemoteFile("https://example.com/f.txt", auth = "my-auth"))
                assertEquals(authCfg, capturedAuth)
            } finally {
                SystemFileSystem.delete(dir)
            }
        }

    // -------------------------------------------------------------------------
    // RepoFile
    // -------------------------------------------------------------------------

    @Test
    fun repoFileWithoutFetcherThrowsContentResolveException() =
        runTest {
            val ex = assertFailsWith<ContentResolveException> {
                ContentResolver(configDir = ".").resolve(ContentSource.RepoFile("README.md"))
            }
            assertEquals("RepoFile", ex.sourceType)
            assertTrue(ex.message?.contains("RepoFileFetcher") == true, ex.message)
        }

    @Test
    fun repoFileDelegatesPathAndRefToFetcher() =
        runTest {
            val fetcher = RepoFileFetcher { path, ref -> Result.success("$path@$ref") }
            val result = ContentResolver(configDir = ".", repoFileFetcher = fetcher)
                .resolve(ContentSource.RepoFile("docs/a.md", "main"))
            assertEquals("docs/a.md@main", result)
        }

    @Test
    fun repoFileFetcherFailureSurfacesAsContentResolveException() =
        runTest {
            val fetcher = RepoFileFetcher { _, _ -> Result.failure(IllegalStateException("boom")) }
            val ex = assertFailsWith<ContentResolveException> {
                ContentResolver(configDir = ".", repoFileFetcher = fetcher).resolve(ContentSource.RepoFile("a.md"))
            }
            assertEquals("RepoFile", ex.sourceType)
            assertEquals("a.md", ex.reference)
            assertTrue(ex.message?.contains("boom") == true, ex.message)
        }

    // -------------------------------------------------------------------------
    // Multiple
    // -------------------------------------------------------------------------

    @Test
    fun multipleConcatenatesWithDefaultSeparator() =
        runTest {
            val dir = tempDir("multiple-default-sep")
            val file1 = Path(dir, "a.txt")
            val file2 = Path(dir, "b.txt")
            try {
                writeFile(file1, "part A")
                writeFile(file2, "part B")
                val result = resolver(dir.toString()).resolve(
                    ContentSource.Multiple(
                        sources = listOf(
                            ContentSource.LocalFile("a.txt"),
                            ContentSource.LocalFile("b.txt"),
                        ),
                    ),
                )
                assertEquals("part A\n\npart B", result)
            } finally {
                SystemFileSystem.delete(file1)
                SystemFileSystem.delete(file2)
                SystemFileSystem.delete(dir)
            }
        }

    @Test
    fun multipleConcatenatesWithCustomSeparator() =
        runTest {
            val dir = tempDir("multiple-custom-sep")
            val file1 = Path(dir, "x.txt")
            val file2 = Path(dir, "y.txt")
            try {
                writeFile(file1, "X")
                writeFile(file2, "Y")
                val result = resolver(dir.toString()).resolve(
                    ContentSource.Multiple(
                        sources = listOf(
                            ContentSource.LocalFile("x.txt"),
                            ContentSource.LocalFile("y.txt"),
                        ),
                        separator = " | ",
                    ),
                )
                assertEquals("X | Y", result)
            } finally {
                SystemFileSystem.delete(file1)
                SystemFileSystem.delete(file2)
                SystemFileSystem.delete(dir)
            }
        }

    @Test
    fun multipleWithInlineSources() =
        runTest {
            val dir = tempDir("multiple-inline")
            try {
                val result = resolver(dir.toString()).resolve(
                    ContentSource.Multiple(
                        sources = listOf(
                            ContentSource.Inline("first"),
                            ContentSource.Inline("second"),
                            ContentSource.Inline("third"),
                        ),
                        separator = ", ",
                    ),
                )
                assertEquals("first, second, third", result)
            } finally {
                SystemFileSystem.delete(dir)
            }
        }

    // -------------------------------------------------------------------------
    // Containment and auth references
    // -------------------------------------------------------------------------

    @Test
    fun localFileCannotEscapeConfigDir() =
        runTest {
            val parent = tempDir("contain")
            val configDir = Path(parent, "config")
            SystemFileSystem.createDirectories(configDir)
            val outside = Path(parent, "secret.txt")
            writeFile(outside, "secret")
            try {
                listOf("../secret.txt", "sub/../../secret.txt", outside.toString(), "..\\secret.txt").forEach { raw ->
                    val ex = assertFailsWith<ContentResolveException>(raw) {
                        resolver(configDir.toString()).resolve(ContentSource.LocalFile(raw))
                    }
                    assertTrue(ex.message?.contains("outside the config directory") == true, ex.message)
                }
            } finally {
                SystemFileSystem.delete(outside)
                SystemFileSystem.delete(configDir)
                SystemFileSystem.delete(parent)
            }
        }

    @Test
    fun localFileInsideConfigDirResolvesViaDotDotAndAbsolutePath() =
        runTest {
            val dir = tempDir("contain-ok")
            val file = Path(dir, "prompt.md")
            writeFile(file, "inside")
            try {
                val resolver = resolver(dir.toString())
                assertEquals("inside", resolver.resolve(ContentSource.LocalFile("sub/../prompt.md")))
                assertEquals("inside", resolver.resolve(ContentSource.LocalFile(file.toString())))
            } finally {
                SystemFileSystem.delete(file)
                SystemFileSystem.delete(dir)
            }
        }

    @Test
    fun unknownAuthReferenceNamesTheKey() =
        runTest {
            val ex = assertFailsWith<ContentResolveException> {
                resolver(".", fetcher = { _, _ -> "never" })
                    .resolve(ContentSource.RemoteFile("https://example.com/p.md", auth = "corp-gitlab"))
            }
            assertTrue(ex.message?.contains("'corp-gitlab'") == true && ex.message?.contains("auth:") == true, ex.message)
        }

    @Test
    fun `auth bound by baseUrl is not sent to another host`() =
        runTest {
            val bound = AuthRegistry(mapOf("corp" to AuthConfig(type = AuthType.TOKEN, token = "s", baseUrl = "https://git.corp.example")))
            var fetched = false

            val ex = assertFailsWith<ContentResolveException> {
                resolver(".", auth = bound, fetcher = { _, _ -> "x".also { fetched = true } })
                    .resolve(ContentSource.RemoteFile("https://evil.example.net/p.md", auth = "corp"))
            }

            assertEquals(false, fetched)
            assertTrue(ex.message?.contains("auth.corp.baseUrl") == true, ex.message)
        }

    @Test
    fun `auth bound by baseUrl still reaches its own host`() =
        runTest {
            val cfg = AuthConfig(type = AuthType.TOKEN, token = "s", baseUrl = "https://GIT.corp.example/group")
            var captured: AuthConfig? = null

            resolver(".", auth = AuthRegistry(mapOf("corp" to cfg)), fetcher = { _, auth -> "x".also { captured = auth } })
                .resolve(ContentSource.RemoteFile("https://git.corp.example/raw/p.md", auth = "corp"))

            assertEquals(cfg, captured)
        }
}
