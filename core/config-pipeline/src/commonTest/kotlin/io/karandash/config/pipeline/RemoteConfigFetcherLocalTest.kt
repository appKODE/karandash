package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.config.AuthConfig
import io.karandash.config.AuthType
import io.karandash.http.HttpAuth
import io.karandash.manifest.ConfigSource
import io.karandash.platform.absolutePathOrSelf
import io.karandash.testfixtures.TestFiles
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Local `extends:` paths, named auth conversion and the content fetcher adapter. */
class RemoteConfigFetcherLocalTest {
    private val stamp = Clock.System.now().toEpochMilliseconds()

    private suspend fun RemoteConfigFetcher.rejects(
        url: String,
        reason: String,
        auth: HttpAuth? = null,
    ) {
        val error = assertFailsWith<ConfigException>(url) { fetch(ConfigSource(url = url, auth = auth)) }
        assertTrue(error.message.orEmpty().contains(reason), "$url: ${error.message}")
    }

    @Test
    fun `a missing extends path hints at the entry to fix`() =
        runTest {
            val fetcher = RemoteConfigFetcher()
            try {
                val error = assertFailsWith<ConfigException> { fetcher.fetch(ConfigSource(url = "nope-$stamp.yaml")) }
                assertTrue(error.message.orEmpty().startsWith("[E0005] Config file not found: nope-$stamp.yaml"), error.message)
                assertEquals(
                    "Create 'nope-$stamp.yaml' or fix the 'extends:' entry that points to it.",
                    error.hint,
                )
            } finally {
                fetcher.close()
            }
        }

    @Test
    fun `local paths are read from the temp directory and rejected when unsafe or missing`() =
        runTest {
            val file = Path(TestFiles.createTestTempDirectory("rcf-local"), "rcf-local.yaml")
            SystemFileSystem.sink(file).buffered().use { it.writeString("key: value") }
            val fetcher = RemoteConfigFetcher()
            try {
                assertEquals("key: value", fetcher.fetch(ConfigSource(url = file.toString())))
                val viaParent = "${file.parent}/sub/../${file.name}"
                assertEquals("key: value", fetcher.fetch(ConfigSource(url = viaParent)), "`..` is collapsed before the root check")
                val missing = listOf("missing-$stamp.yaml", "$file.missing", "C:\\missing-$stamp.yaml", "\\\\server\\missing-$stamp.yaml")
                missing.forEach { path -> fetcher.rejects(path, "not found") }
                fetcher.rejects(file.toString(), "not supported for local file", HttpAuth.Bearer("t"))
                if (SystemFileSystem.exists(Path("/etc/hosts"))) fetcher.rejects("/etc/hosts", "outside the project root (")
            } finally {
                SystemFileSystem.delete(file)
                fetcher.close()
            }
            assertFailsWith<IllegalStateException> { fetcher.fetchFileContent("https://h/x", null) }
        }

    @Test
    fun `named auth entries convert to request credentials or name the missing field`() {
        val complete = mapOf(
            AuthConfig(AuthType.TOKEN, token = "t") to HttpAuth.Bearer("t"),
            AuthConfig(AuthType.BEARER, token = "t") to HttpAuth.Bearer("t"),
            AuthConfig(AuthType.BASIC, username = "u", password = "p") to HttpAuth.Basic("u", "p"),
            AuthConfig(AuthType.HEADER, name = "X-Key", value = "v") to HttpAuth.CustomHeader("X-Key", "v"),
        )
        complete.forEach { (config, auth) -> assertEquals(auth, config.toHttpAuth()) }
        val incomplete = mapOf(
            AuthConfig(AuthType.TOKEN) to "token",
            AuthConfig(AuthType.BASIC, password = "p") to "username",
            AuthConfig(AuthType.BASIC, username = "u") to "password",
            AuthConfig(AuthType.HEADER, value = "v") to "name",
            AuthConfig(AuthType.HEADER, name = "n") to "value",
        )
        incomplete.forEach { (config, field) ->
            val error = assertFailsWith<ConfigException> { config.toHttpAuth() }
            assertTrue(error.message.orEmpty().endsWith("missing '$field'"), error.message)
        }
    }

    @Test
    fun `the content fetcher adapter sends the converted credentials`() =
        runTest {
            val headers = mutableListOf<String?>()
            val client = HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        headers += request.headers[HttpHeaders.Authorization]
                        respond("body", HttpStatusCode.OK)
                    }
                }
            }
            val fetcher = RemoteConfigFetcher(client).asContentFetcher()

            assertEquals("body", fetcher.fetch("https://h/a.md", AuthConfig(AuthType.BEARER, token = "t")))
            assertEquals("body", fetcher.fetch("https://h/b.md", null))
            assertEquals(listOf("Bearer t", null), headers)
        }

    @Test
    fun `a parent outside the project root is blocked with the root named`() =
        runTest {
            if (!SystemFileSystem.exists(Path("/etc/hosts"))) return@runTest
            val root = TestFiles.createTestTempDirectory("rcf-root")
            val fetcher = RemoteConfigFetcher()
            try {
                val error = assertFailsWith<ConfigException> { fetcher.fetch(ConfigSource(url = "/etc/hosts"), listOf(root)) }
                assertTrue(error.message.orEmpty().contains("outside the project root"), error.message)
                assertTrue(error.message.orEmpty().contains(root.toString()), error.message)
            } finally {
                fetcher.close()
            }
        }

    @Test
    fun `the project root is the git top-level or the directory holding the discovered karandash dir`() {
        val repo = TestFiles.createTestTempDirectory("rcf-git")
        val pkg = Path(repo, "packages/frontend/.karandash")
        SystemFileSystem.createDirectories(pkg)
        SystemFileSystem.createDirectories(Path(repo, ".git"))
        val inRepo = Path(pkg, "config.yaml")
        SystemFileSystem.sink(inRepo).buffered().use { it.writeString("extends: ../../../base.yaml") }
        assertTrue(repo.absolutePathOrSelf() in localProjectRoots(inRepo.toString()), "${localProjectRoots(inRepo.toString())}")

        val plain = TestFiles.createTestTempDirectory("rcf-nogit")
        val discovered = Path(plain, ".karandash")
        SystemFileSystem.createDirectories(Path(plain, "sub/deep"))
        SystemFileSystem.createDirectories(discovered)
        val config = Path(discovered, "config.yaml")
        SystemFileSystem.sink(config).buffered().use { it.writeString("title-format: {}") }
        // CI may put the temp dir inside a checkout (TMPDIR under the build dir): then that checkout is the root.
        val expected = generateSequence(plain) { it.parent }.firstOrNull { SystemFileSystem.exists(Path(it, ".git")) } ?: plain
        assertTrue(expected.absolutePathOrSelf() in localProjectRoots(config.toString()), "${localProjectRoots(config.toString())}")

        assertEquals(1, localProjectRoots("https://example.com/config.yaml").size, "a remote root adds no local directory")
    }
}
