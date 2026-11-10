package io.karandash.cli.commands.cache

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cache.CacheManager
import io.karandash.cache.cas.CacheIndexEntry
import io.karandash.cache.cas.CacheIndexStore
import io.karandash.cache.cas.CasBlobStore
import io.karandash.cli.errors.ExitCodes
import io.karandash.cli.pipeline.ConfigLoader
import io.karandash.platform.getUserHomeDirectoryString
import io.karandash.testfixtures.TestFiles
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * The `cache` subcommands use `cache.dir` of the config when `--cache-dir` is absent,
 * through the same resolver `validate` uses. `validate` keeps resolving `cache.dir` against the CWD with `~` expansion
 * (consumer GitLab caches `cache/` and `cache/karandash` depend on it).
 */
class CacheDirConfigTest {
    private val work = TestFiles.createTestTempDirectory("karandash-cache-dir")
    private val configured = Path(work, "from-config")
    private val explicit = Path(work, "from-flag")

    private suspend fun bootstrap() = PluginBootstrap().apply { loadPlugins() }

    private fun notFound() = MockEngine { respond(ByteArray(0), HttpStatusCode.NotFound) }

    private fun config(): String =
        Path(work, "config.json").also { TestFiles.writeText(it, """{"cache":{"dir":"$configured"}}""") }.toString()

    @Test
    fun `cache stats uses cache dir of the config`() =
        runTest {
            val result = CacheStatsCommand(bootstrap()).test(listOf("--config", config()))
            assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
            assertTrue("Base dir : $configured" in result.output, result.output)
        }

    @Test
    fun `cache dir flag wins over the config`() =
        runTest {
            val result = CacheStatsCommand(bootstrap()).test(listOf("--config", config(), "--cache-dir", explicit.toString()))
            assertTrue("Base dir : $explicit" in result.output, result.output)
        }

    @Test
    fun `cache gc uses cache dir of the config`() =
        runTest {
            val result = CacheGcCommand(bootstrap()).test(listOf("--config", config()))
            assertTrue("$configured" in result.output, result.output)
        }

    @Test
    fun `cache clear uses cache dir of the config`() =
        runTest {
            TestFiles.writeText(Path(configured, "cas", "ab", "cd"), "x")
            val result = CacheClearCommand(bootstrap()).test(listOf("--config", config()))
            assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
            assertTrue("Blobs deleted   : 1" in result.output, result.output)
        }

    @Test
    fun `cache remote-stats uses cache dir of the config`() =
        runTest {
            TestFiles.writeText(Path(configured, "cas", "ab", "cd"), "x")
            val result = CacheRemoteStatsCommand(bootstrap()).test(listOf("--config", config()))
            assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
            assertTrue("Blobs        : 1" in result.output, result.output)
        }

    @Test
    fun `cache remote-stats shows cache remote of the config and the flags override it`() =
        runTest {
            val path = Path(work, "remote.json")
            TestFiles.writeText(path, """{"cache":{"dir":"$configured","remote":{"backend":"s3","url":"https://cache.example.com"}}}""")

            val fromConfig = CacheRemoteStatsCommand(bootstrap(), notFound(), awsEnv = { null }).test(listOf("--config", path.toString()))
            assertTrue("Backend type : s3" in fromConfig.output, fromConfig.output)
            assertTrue("Backend URL  : https://cache.example.com" in fromConfig.output, fromConfig.output)
            assertTrue("Reachable    : yes" in fromConfig.output, fromConfig.output)

            val overridden = CacheRemoteStatsCommand(bootstrap(), notFound(), awsEnv = {
                null
            }).test(listOf("--config", path.toString(), "--backend-type", "http"))
            assertTrue("Backend type : http" in overridden.output, overridden.output)
        }

    @Test
    fun `cache gc remote expires entries older than cache remote ttl and deletes their blobs on the remote`() =
        runTest {
            val now = Clock.System.now().toEpochMilliseconds()
            val day = 24L * 60 * 60 * 1000
            val blobs = CasBlobStore(Path(configured, "cas"))
            val old = blobs.put("old".encodeToByteArray())
            val fresh = blobs.put("fresh".encodeToByteArray())
            val index = CacheIndexStore(Path(configured, "index"))
            index.write("rules", "old", CacheIndexEntry(digest = old.hex, expiresAt = now + day, createdAt = now - 2 * day))
            index.write("rules", "fresh", CacheIndexEntry(digest = fresh.hex, expiresAt = now + day, createdAt = now))
            val path = Path(work, "ttl.json")
            val remote = """{"backend":"http","url":"https://cache.example.com","ttl":"1d"}"""
            TestFiles.writeText(path, """{"cache":{"dir":"$configured","remote":$remote}}""")
            val requests = mutableListOf<String>()
            val engine = MockEngine { request ->
                requests += "${request.method.value} ${request.url.encodedPath}"
                respond(ByteArray(0), HttpStatusCode.NoContent)
            }

            val local = CacheGcCommand(bootstrap(), engine).test(listOf("--config", path.toString()))
            assertEquals(ExitCodes.SUCCESS, local.statusCode, local.output)
            assertTrue(blobs.exists(old), "without --remote the ttl does not apply")
            assertTrue(requests.isEmpty(), requests.toString())

            val result = CacheGcCommand(bootstrap(), engine).test(listOf("--config", path.toString(), "--remote"))
            assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
            assertTrue("Expired indexes removed : 1" in result.output, result.output)
            assertTrue("Remote blobs deleted    : 1" in result.output, result.output)
            assertEquals(listOf("DELETE /cas/${old.shard}/${old.remainder}"), requests)
            assertFalse(blobs.exists(old))
            assertTrue(blobs.exists(fresh))
        }

    @Test
    fun `cache gc remote without a remote url is a config error`() =
        runTest {
            val result = CacheGcCommand(bootstrap()).test(listOf("--config", config(), "--remote"))
            assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode, result.output)
        }

    @Test
    fun `validate resolves cache dir against the CWD and expands the home prefix`() =
        runTest {
            val loader = ConfigLoader.forCommand(null, bootstrap())
            assertEquals(Path("cache/karandash"), loader.resolveCacheDirPath("cache/karandash"))
            assertEquals(Path("cache"), loader.resolveCacheDirPath("cache"))
            assertEquals(Path(getUserHomeDirectoryString(), "x/y"), loader.resolveCacheDirPath("~/x/y"))
        }

    @Test
    fun `cache stats expands the home prefix of cache dir`() =
        runTest {
            val cfg = Path(work, "home.json").also { TestFiles.writeText(it, """{"cache":{"dir":"~/karandash-brk33"}}""") }
            val result = CacheStatsCommand(bootstrap()).test(listOf("--config", cfg.toString()))
            assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
            assertTrue("Base dir : ${Path(getUserHomeDirectoryString(), "karandash-brk33")}" in result.output, result.output)
        }

    @Test
    fun `cache stats on a config that does not load exits 2`() =
        runTest {
            val cfg = Path(work, "broken.json").also { TestFiles.writeText(it, "{ not json") }
            val result = CacheStatsCommand(bootstrap()).test(listOf("--config", cfg.toString()))
            assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode, result.output)
            assertTrue("Config load failed" in result.output, result.output)
        }

    @Test
    fun `cache push reads blobs from cache dir of the config`() =
        runTest {
            val cfg = Path(work, "push.json").also {
                TestFiles.writeText(it, """{"cache":{"dir":"$configured","remote":{"backend":"http","url":"https://cache.example.com"}}}""")
            }
            val result = CachePushCommand(bootstrap()).test(listOf("--config", cfg.toString()))
            assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
            assertTrue("No local CAS blobs found at ${Path(configured, CacheManager.CAS_BLOBS_DIR)}" in result.output, result.output)
        }
}
