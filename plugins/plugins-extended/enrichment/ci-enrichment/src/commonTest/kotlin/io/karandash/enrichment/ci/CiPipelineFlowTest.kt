package io.karandash.enrichment.ci

import io.karandash.api.PluginContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.models.ChangeType
import io.karandash.models.CiJobStatus
import io.karandash.models.FileChange
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.Logger
import io.karandash.sdk.enrichment.EnrichmentKeys
import io.karandash.testfixtures.TestFiles
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CiPipelineFlowTest {
    private val failedMr =
        createTestMergeRequest().copy(
            ciJobs = listOf(CiJobStatus("lint", "failed"), CiJobStatus("unit", "failed"), CiJobStatus("ok", "success")),
        )

    private fun engine(
        requests: MutableList<String>,
        route: (String) -> Pair<HttpStatusCode, String>,
    ) = MockEngine { request ->
        val path = request.url.encodedPath
        requests += path
        val (status, body) = route(path)
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private suspend fun failedJobs(
        route: (String) -> Pair<HttpStatusCode, String>,
        config: String = API_CONFIG,
        requests: MutableList<String> = mutableListOf(),
        ctx: TestPluginContext = TestPluginContext(KarandashJson.Default.parseToJsonElement(config)),
    ): List<FailedJob>? {
        val provider = CiEnrichmentProvider(engine(requests, route))
        provider.initialize(ctx)
        val result = provider.enrich(failedMr, JsonObject(emptyMap()), EnrichmentContext.EMPTY)
        provider.shutdown()
        return result[EnrichmentKeys.CI_FAILED_JOBS]?.let {
            KarandashJson.Default.decodeFromJsonElement(ListSerializer(FailedJob.serializer()), it)
        }
    }

    @Test
    fun `api jobs carry stage url finish time and an error snippet from the trace`() =
        runTest {
            val requests = mutableListOf<String>()
            val jobs =
                failedJobs(
                    route = { path ->
                        when {
                            path.endsWith("/merge_requests/1/pipelines") -> HttpStatusCode.OK to """[{"id":7,"status":"failed"}]"""
                            path.endsWith("/pipelines/7/jobs") -> HttpStatusCode.OK to JOBS
                            path.endsWith("/jobs/11/trace") -> HttpStatusCode.OK to "compiling\nerror: boom\ndone"
                            else -> HttpStatusCode.InternalServerError to ""
                        }
                    },
                    config = API_CONFIG.replace("}", ""","failed-jobs":{"max-jobs":2}}"""),
                    requests = requests,
                )

            assertEquals(
                listOf(
                    FailedJob("unit", "test", "https://gitlab.acme.com/j/11", "error: boom", "2026-01-01T00:00:00Z"),
                    FailedJob("lint", null, null, null, null),
                ),
                jobs,
            )
            // the 500 on the lint trace is a GET, retried twice by default.
            assertEquals(6, requests.size)
        }

    @Test
    fun `api failures fall back to the failed jobs listed on the MR`() =
        runTest {
            val names = listOf("lint", "unit")
            val notFound: (String) -> Pair<HttpStatusCode, String> = { HttpStatusCode.NotFound to "" }
            val noPipelines: (String) -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to "[]" }
            val noJobs: (String) -> Pair<HttpStatusCode, String> = { path ->
                HttpStatusCode.OK to if (path.endsWith("/pipelines")) """[{"id":7,"status":"failed"}]""" else "[]"
            }
            val jobsDown: (String) -> Pair<HttpStatusCode, String> = { path ->
                if (path.endsWith("/pipelines")) {
                    HttpStatusCode.OK to """[{"id":7,"status":"failed"}]"""
                } else {
                    HttpStatusCode.BadGateway to
                        ""
                }
            }

            for (route in listOf(notFound, noPipelines, noJobs, jobsDown)) {
                assertEquals(names, failedJobs(route)?.map { it.name })
            }
        }

    @Test
    fun `malformed api bodies warn and fall back`() =
        runTest {
            val badPipelines = TestPluginContext(KarandashJson.Default.parseToJsonElement(API_CONFIG))
            assertEquals(listOf("lint", "unit"), failedJobs({ HttpStatusCode.OK to "not json" }, ctx = badPipelines)?.map { it.name })
            assertTrue(badPipelines.warnings.any { it.startsWith("CI enrichment: failed to fetch pipelines for MR 1:") })

            val badJobs = TestPluginContext(KarandashJson.Default.parseToJsonElement(API_CONFIG))
            val route: (String) -> Pair<HttpStatusCode, String> = { path ->
                HttpStatusCode.OK to if (path.endsWith("/pipelines")) """[{"id":7,"status":"failed"}]""" else "{"
            }
            assertEquals(listOf("lint", "unit"), failedJobs(route, ctx = badJobs)?.map { it.name })
            assertTrue(badJobs.warnings.any { it.startsWith("CI enrichment: failed to fetch jobs for pipeline 7:") })
        }

    @Test
    fun `without api config the MR jobs are capped by max jobs`() =
        runTest {
            val requests = mutableListOf<String>()
            val jobs = failedJobs({ HttpStatusCode.OK to "[]" }, """{"failed-jobs":{"max-jobs":1}}""", requests)

            assertEquals(listOf(FailedJob("lint")), jobs)
            assertTrue(requests.isEmpty())
        }

    @Test
    fun `disabled sub blocks emit nothing`() =
        runTest {
            val requests = mutableListOf<String>()
            val provider = CiEnrichmentProvider(engine(requests) { HttpStatusCode.OK to "[]" })
            val config = """{"failed-jobs":{"enabled":false},"coverage-gaps":{"enabled":false}}"""
            provider.initialize(TestPluginContext(KarandashJson.Default.parseToJsonElement(config)))

            assertTrue(provider.enrich(failedMr, JsonObject(emptyMap()), EnrichmentContext.EMPTY).isEmpty())
            assertTrue(requests.isEmpty())
        }

    @Test
    fun `invalid sub block value falls back to defaults with a warning`() =
        runTest {
            val ctx = TestPluginContext(KarandashJson.Default.parseToJsonElement("""{"failed-jobs":{"max-jobs":"many"}}"""))
            CiEnrichmentProvider().initialize(ctx)

            assertTrue(ctx.warnings.any { it.startsWith("enrichment.ci: invalid config, using defaults:") })
        }

    @Test
    fun `coverage gaps merge lcov and jacoco reports for changed files only`() =
        runTest {
            val root = tempDir()
            write(
                Path(root, "app/build/lcov.info"),
                "SF:src/main/Foo.kt\nDA:1,0\nDA:2,0\nDA:3,1\nDA:5,0\nend_of_record\nSF:src/Other.kt\nDA:1,0\nend_of_record\n",
            )
            write(
                Path(root, "lib/reports/jacoco.xml"),
                """
                <package name="com/acme">
                <sourcefile name="Bar.kt">
                <line nr="4" mi="2" ci="0"/>
                <line nr="5" mi="0" ci="3"/>
                </sourcefile>
                </package>
                """.trimIndent(),
            )
            write(Path(root, "notes/readme.xml"), "<line nr=\"1\" mi=\"1\"/>")
            val mr =
                createTestMergeRequest(
                    changes =
                        listOf(
                            FileChange(path = "src/main/Foo.kt", changeType = ChangeType.MODIFIED),
                            FileChange(path = "lib/src/main/kotlin/com/acme/Bar.kt", changeType = ChangeType.MODIFIED),
                            FileChange(path = "", changeType = ChangeType.MODIFIED),
                        ),
                )
            val provider = CiEnrichmentProvider()
            provider.initialize(dirContext(root.toString()))

            val gaps = provider.enrich(mr, JsonObject(emptyMap()), EnrichmentContext.EMPTY)[EnrichmentKeys.CI_COVERAGE_GAPS]

            assertEquals(
                mapOf(
                    "src/main/Foo.kt" to listOf(UncoveredRange(1, 2), UncoveredRange(5, 5)),
                    "com/acme/Bar.kt" to listOf(UncoveredRange(4, 4)),
                ),
                (gaps as JsonObject).mapValues { (_, ranges) ->
                    KarandashJson.Default.decodeFromJsonElement(ListSerializer(UncoveredRange.serializer()), ranges)
                },
            )
        }

    @Test
    fun `artifact patterns choose which coverage reports are read`() =
        runTest {
            val root = tempDir()
            write(Path(root, "coverage/app.lcov"), "SF:src/A.kt\nDA:7,0\nend_of_record\n")
            write(Path(root, "build/lcov.info"), "SF:src/A.kt\nDA:1,0\nend_of_record\n")
            val config = KarandashJson.Default.parseToJsonElement("""{"coverage-gaps":{"artifact-patterns":["coverage/*.lcov"]}}""")
            val provider = CiEnrichmentProvider()
            provider.initialize(dirContext(root.toString(), config))

            assertEquals(mapOf("src/A.kt" to listOf(UncoveredRange(7, 7))), coverageGaps(provider, "src/A.kt"))
        }

    @Test
    fun `default artifact patterns still find lcov jacoco and aggregated jacoco reports`() =
        runTest {
            val root = tempDir()
            write(Path(root, "lcov.info"), "SF:src/A.kt\nDA:1,0\nend_of_record\n")
            write(
                Path(root, "m/jacoco-aggregated.xml"),
                "<package name=\"p\"><sourcefile name=\"B.kt\"><line nr=\"2\" mi=\"1\" ci=\"0\"/></sourcefile></package>"
            )
            write(
                Path(root, "m/test-jacoco.xml"),
                "<package name=\"p\"><sourcefile name=\"C.kt\"><line nr=\"3\" mi=\"1\" ci=\"0\"/></sourcefile></package>"
            )
            write(Path(root, "m/other.lcov"), "SF:src/D.kt\nDA:4,0\nend_of_record\n")
            val provider = CiEnrichmentProvider()
            provider.initialize(dirContext(root.toString()))

            assertEquals(
                mapOf(
                    "src/A.kt" to listOf(UncoveredRange(1, 1)),
                    "p/B.kt" to listOf(UncoveredRange(2, 2)),
                    "p/C.kt" to listOf(UncoveredRange(3, 3)),
                ),
                coverageGaps(provider, "src/A.kt", "src/p/B.kt", "src/p/C.kt", "src/D.kt"),
            )
        }

    private suspend fun coverageGaps(
        provider: CiEnrichmentProvider,
        vararg paths: String,
    ): Map<String, List<UncoveredRange>> {
        val mr = createTestMergeRequest(changes = paths.map { FileChange(path = it, changeType = ChangeType.MODIFIED) })
        val gaps = provider.enrich(mr, JsonObject(emptyMap()), EnrichmentContext.EMPTY)[EnrichmentKeys.CI_COVERAGE_GAPS]
        return (gaps as? JsonObject).orEmpty().mapValues { (_, ranges) ->
            KarandashJson.Default.decodeFromJsonElement(ListSerializer(UncoveredRange.serializer()), ranges)
        }
    }

    @Test
    fun `coverage scan returns nothing for blank or missing roots and empty changes`() {
        val missing = Path(TestFiles.createTestTempDirectory("ci-missing"), "absent").toString()
        val empty = tempDir().toString()

        assertTrue(CiCoverageScanner.scan("", listOf("a.kt")).isEmpty())
        assertTrue(CiCoverageScanner.scan(empty, emptyList()).isEmpty())
        assertTrue(CiCoverageScanner.scan(missing, listOf("a.kt")).isEmpty())
        assertTrue(CiCoverageScanner.scan(empty, listOf("a.kt")).isEmpty())
    }

    private fun tempDir(): Path {
        val dir = TestFiles.createTestTempDirectory("ci-cov")
        SystemFileSystem.createDirectories(dir)
        return dir
    }

    private fun write(
        file: Path,
        content: String,
    ) {
        SystemFileSystem.createDirectories(checkNotNull(file.parent))
        SystemFileSystem.sink(file).buffered().use { it.writeString(content) }
    }

    private fun dirContext(
        dir: String,
        config: JsonElement = JsonObject(emptyMap()),
    ) = object : PluginContext {
        private val base = TestPluginContext(config)
        override val pluginConfig = base.pluginConfig
        override val logger: Logger = base.logger
        override val hostVersion = "test"
        override val workingDirectory = dir

        override fun env(name: String): String? = null

        override fun warn(message: String) {}

        override fun log(message: String) {}
    }

    private companion object {
        const val API_CONFIG = """{"url":"https://gitlab.acme.com","project-id":"9","token":"t"}"""
        const val JOBS =
            """[{"id":11,"name":"unit","stage":"test","web_url":"https://gitlab.acme.com/j/11",""" +
                """"finished_at":"2026-01-01T00:00:00Z","status":"failed"},{"id":12,"name":"lint"},{"id":13,"name":"extra"}]"""
    }
}
