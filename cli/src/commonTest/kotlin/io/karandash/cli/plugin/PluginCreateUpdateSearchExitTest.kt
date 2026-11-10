package io.karandash.cli.plugin

import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test
import io.karandash.cli.TestFileHelpers
import io.karandash.cli.commands.PluginCreateCommand
import io.karandash.cli.errors.ExitCodes
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.io.files.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Happy paths and exit codes of `plugin create`, `plugin update` and `plugin search`. */
class PluginCreateUpdateSearchExitTest {
    private val tempDir = TestFileHelpers.createTempDir("plugin-create-update")

    @AfterTest
    fun cleanup() = TestFileHelpers.deleteTempDir(tempDir)

    private fun update(
        engine: MockEngine,
        vararg args: String,
    ): CliktCommandTestResult {
        val cache = Path(tempDir, "cache-${Random.nextInt(0, Int.MAX_VALUE)}")
        return PluginUpdateCommand(FakePluginLister(), registryClient = { it.registryClient(cacheDir = cache, engine = engine) })
            .test(args.toList() + listOf("--plugin-dir", tempDir.toString()))
    }

    @Test
    fun `create scaffolds a plugin project in the working directory`() {
        val name = "k5-create-${Random.nextInt(0, Int.MAX_VALUE)}"
        val projectDir = Path("$name-plugin")
        try {
            val result = PluginCreateCommand().test(listOf(name, "--type", "reporter", "--package", "com.example.k5", "--no-git"))
            assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
            assertTrue("Created reporter plugin: $name-plugin/" in result.output, result.output)
            assertTrue(TestFileHelpers.exists(Path(projectDir, "build.gradle.kts")), result.output)
        } finally {
            TestFileHelpers.deleteTempDir(projectDir)
        }
    }

    @Test
    fun `create rejects a bad name and an unknown type`() {
        val badName = PluginCreateCommand().test(listOf("Bad_Name", "--no-git"))
        assertEquals(ExitCodes.VALIDATION_FAILED, badName.statusCode)
        assertTrue("lowercase alphanumeric" in badName.stderr, badName.output)

        val badType = PluginCreateCommand().test(listOf("ok-name", "--type", "widget", "--no-git"))
        assertEquals(ExitCodes.VALIDATION_FAILED, badType.statusCode)
        assertTrue("Invalid --type 'widget'" in badType.stderr, badType.output)
    }

    @Test
    fun `update check only lists the available update`() {
        val result = update(registryEngine("https://example.com/acme.jar"), "--check-only")
        assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
        assertTrue("update available" in result.output, result.output)
        assertTrue("1 update(s) available." in result.output, result.output)
    }

    @Test
    fun `update of an unknown plugin is a config error`() {
        val result = update(registryEngine("https://example.com/acme.jar"), "nope")
        assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode, result.output)
        assertTrue("Plugin not found: nope" in result.stderr, result.output)
    }

    @Test
    fun `update with an unreachable registry is a config error`() {
        val result = update(MockEngine { respondError(HttpStatusCode.InternalServerError) }, "acme")
        assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode, result.output)
        assertTrue("Failed to fetch registry" in result.stderr, result.output)
    }

    @Test
    fun `failed update installs are an execution error`() {
        val plainHttp = update(registryEngine("http://example.com/acme.jar"), "--yes")
        assertEquals(ExitCodes.EXECUTION_ERROR, plainHttp.statusCode, plainHttp.output)
        assertTrue("Only HTTPS download URLs are allowed" in plainHttp.stderr, plainHttp.output)

        val refused = update(registryEngine("https://example.com/acme.jar"), "--yes")
        assertEquals(ExitCodes.EXECUTION_ERROR, refused.statusCode, refused.output)
        assertTrue("download refused" in refused.stderr, refused.output)
        assertTrue("1 plugin(s) failed to update." in refused.output, refused.output)
    }

    @Test
    fun `search with an unreachable registry and no cache is a config error`() {
        val command = PluginSearchCommand(
            registryClient = { it.registryClient(cacheDir = tempDir, engine = MockEngine { respondError(HttpStatusCode.BadGateway) }) },
        )
        val result = command.test(listOf("--refresh"))
        assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode, result.output)
        assertTrue("Failed to fetch registry" in result.stderr, result.output)
    }
}
