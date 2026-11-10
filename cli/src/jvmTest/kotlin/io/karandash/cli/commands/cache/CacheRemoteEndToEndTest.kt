package io.karandash.cli.commands.cache

import com.github.ajalt.clikt.testing.test
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cache.CacheManager
import io.karandash.cli.commands.validate.ValidateCommand
import io.karandash.cli.compat.ConsoleCapture
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * End to end: a `validate` run whose config sets `cache.remote` (a loopback HTTP backend) and
 * `cache.responses.persist: true` pushes the blobs it caches to the backend, keeps the provider responses in the
 * on-disk `http-responses` namespace and saves its hit and miss counts for `cache stats`.
 */
class CacheRemoteEndToEndTest {
    @Test
    fun `cache remote in the config pushes blobs and the run stats are saved`() =
        runTest(timeout = 2.minutes) {
            val puts = Collections.synchronizedList(mutableListOf<String>())
            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            server.createContext("/") { exchange -> handle(exchange, puts) }
            server.start()
            val workDir = java.nio.file.Paths
                .get(TestFiles.createTestTempDirectory("karandash-cache-remote-e2e").toString())
            val cacheDir = workDir.resolve("cache")
            val (result, _, stderr) = try {
                val baseUrl = "http://localhost:${server.address.port}"
                val config = workDir.resolve("config.yaml")
                config.writeText(config(baseUrl, cacheDir.toString()))
                val bootstrap = PluginBootstrap(envReader = { null })
                bootstrap.loadPluginsFromDirs(emptyList())
                ConsoleCapture.capture {
                    try {
                        ValidateCommand(bootstrap).test(
                            listOf(
                                "--no-auto-detect",
                                "--allow-localhost",
                                "--config",
                                config.toString(),
                                "--platform",
                                "gitlab",
                                "--project",
                                "group/repo",
                                "--mr-id",
                                "1",
                            ),
                        )
                    } finally {
                        bootstrap.shutdown()
                    }
                }
            } finally {
                server.stop(0)
            }

            val output = result.output + result.stderr + stderr
            assertTrue(puts.isNotEmpty(), "no blob was pushed to the remote backend: $output")
            assertTrue(cacheDir.resolve(CacheManager.RUN_STATS_FILE).exists(), "no run stats saved: $output")
            val responses = cacheDir.resolve("index").resolve("http-responses")
            assertTrue(responses.exists() && responses.listDirectoryEntries().isNotEmpty(), "responses not persisted: $output")
        }

    private fun handle(
        exchange: HttpExchange,
        puts: MutableList<String>,
    ) {
        val method = exchange.requestMethod
        val path = exchange.requestURI.rawPath
        exchange.requestBody.readAllBytes()
        if (path.startsWith("/cachebk")) {
            if (method == "PUT") puts += path
            exchange.sendResponseHeaders(if (method == "PUT") HTTP_CREATED else HTTP_NOT_FOUND, -1)
            exchange.close()
            return
        }
        val response = when {
            method != "GET" -> CREATED_NOTE
            path == "/api/v4/user" -> BOT_USER
            path.endsWith("/merge_requests/1") -> MR
            path.endsWith("/changes") -> CHANGES
            else -> "[]"
        }.encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(if (method == "POST") HTTP_CREATED else HTTP_OK, response.size.toLong())
        exchange.responseBody.use { it.write(response) }
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val HTTP_NOT_FOUND = 404

        fun config(
            baseUrl: String,
            cacheDir: String,
        ) = """
            allow-localhost: true
            cache:
              dir: "$cacheDir"
              responses:
                persist: true
              remote:
                backend: http
                url: "$baseUrl/cachebk"
            platforms:
              gitlab:
                url: "$baseUrl"
                token: "e2e-token"
            rules:
              title-format:
                pattern: "^JIRA-[0-9]+: .+"
            """.trimIndent()

        const val BOT_USER = """{"id": 99, "username": "karandash-bot", "name": "Karandash Bot"}"""
        val MR =
            """
            {"id": 101, "iid": 1, "title": "Add feature", "description": "E2E MR.",
             "source_branch": "feature/e2e", "target_branch": "main",
             "author": {"id": 7, "username": "dev", "name": "Dev"}, "state": "opened",
             "labels": [], "diff_refs": {"base_sha": "b0", "head_sha": "h0", "start_sha": "s0"}}
            """.trimIndent()
        val CHANGES =
            """
            {"changes": [{"old_path": "a.kt", "new_path": "a.kt", "new_file": false, "renamed_file": false,
             "deleted_file": false, "diff": "@@ -1 +1 @@\n-a\n+b\n"}]}
            """.trimIndent()
        const val CREATED_NOTE = """{"id": 1, "body": "", "notes": []}"""
    }
}
