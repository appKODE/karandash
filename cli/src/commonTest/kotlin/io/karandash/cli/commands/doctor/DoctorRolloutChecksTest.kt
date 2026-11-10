package io.karandash.cli.commands.doctor

import io.karandash.cache.CacheConfig
import io.karandash.cli.TestFileHelpers
import io.karandash.config.Config
import io.karandash.logger.NoOpLogger
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DoctorRolloutChecksTest {
    private val tempDir = TestFileHelpers.createTempDir("karandash-doctor-rollout-test")

    @AfterTest
    fun cleanup() = TestFileHelpers.deleteTempDir(tempDir)

    private fun checks(
        offline: Boolean = false,
        env: Map<String, String> = emptyMap(),
        engine: MockEngine? = null,
    ) = DoctorRolloutChecks(NoOpLogger, offline, env::get, engine)

    private fun aiConfig(baseUrl: String) =
        Config(allowLocalhost = true, ai = JsonObject(mapOf("provider" to JsonPrimitive("openai"), "baseUrl" to JsonPrimitive(baseUrl))))

    @Test
    fun `the extends chain is listed and never warns about the base ref version`() {
        val base = "https://git.example/review-bot/-/raw/1.0.0/base/config.yaml"
        assertTrue(checks().extendsChain(listOf(base, ".karandash/config.yaml")).all { it is DoctorCheckResult.Pass })
        assertTrue(checks().extendsChain(listOf(".karandash/config.yaml")).single() is DoctorCheckResult.Pass)
    }

    @Test
    fun `token names report set or missing and never the value`() {
        val layer = JsonObject(
            mapOf(
                "platforms" to JsonObject(mapOf("gitlab" to JsonObject(mapOf("token" to JsonPrimitive("\${GITLAB_TOKEN}"))))),
                "ai" to JsonObject(mapOf("apiKey" to JsonPrimitive("\${AI_API_KEY}"), "model" to JsonPrimitive("\${AI_MODEL}"))),
            ),
        )
        val results = checks(env = mapOf("GITLAB_TOKEN" to "secret-value")).tokens(listOf(layer))
        assertEquals(listOf("AI_API_KEY: missing", "GITLAB_TOKEN: set"), results.map { it.message })
        assertIs<DoctorCheckResult.Warn>(results.first())
        assertFalse(results.any { "secret-value" in it.message })
    }

    @Test
    fun `cache dir probe passes on a writable dir and fails on a file path`() {
        val dir = Path(tempDir, "cache")
        assertIs<DoctorCheckResult.Pass>(checks().cacheDir(Config(cache = CacheConfig(dir = dir.toString()))))
        assertFalse(SystemFileSystem.exists(Path(dir, ".doctor-probe")))
        val file = Path(tempDir, "plain-file")
        TestFileHelpers.writeFile(file, "x")
        assertIs<DoctorCheckResult.Fail>(checks().cacheDir(Config(cache = CacheConfig(dir = Path(file, "sub").toString()))))
        assertIs<DoctorCheckResult.Pass>(checks().cacheDir(Config(cache = CacheConfig(enabled = false, dir = file.toString()))))
    }

    @Test
    fun `ai endpoint answering any status passes and a network error fails`() =
        runTest {
            val up = MockEngine { respond("", HttpStatusCode.Unauthorized) }
            assertIs<DoctorCheckResult.Pass>(checks(engine = up).aiEndpoint(aiConfig("http://localhost:1/v1")))
            val down = MockEngine { throw IOException("connection refused") }
            assertIs<DoctorCheckResult.Fail>(checks(engine = down).aiEndpoint(aiConfig("http://localhost:1/v1")))
        }

    @Test
    fun `ai endpoint is not probed offline or without ai`() =
        runTest {
            val never = MockEngine { error("must not be called") }
            assertIs<DoctorCheckResult.Pass>(checks(offline = true, engine = never).aiEndpoint(aiConfig("http://localhost:1/v1")))
            assertIs<DoctorCheckResult.Pass>(checks(engine = never).aiEndpoint(Config()))
            assertIs<DoctorCheckResult.Warn>(checks(engine = never).aiEndpoint(aiConfig("\${AI_BASE_URL}")))
            assertEquals(null, checks(engine = never).remoteCache(Config(), emptyList()))
        }
}
