package io.karandash.registry

import io.karandash.api.ModuleType
import io.karandash.testfixtures.TestFiles
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `setExecutable` and `absolutePath` are used by the installer and the scaffold, not only defined. */
class ExecutableBitTest {
    private val dir = java.nio.file.Paths
        .get(TestFiles.createTestTempDirectory("exec-bit").toString())
        .toFile()

    private suspend fun install(
        extension: String,
        formats: Set<String>,
    ) = PluginInstallUpdateEngine
        .downloadAndUpdate(
            downloadUrl = "https://registry.test/p.$extension",
            pluginDirPath = dir.path,
            oldSourcePath = null,
            pluginId = "p",
            supportedFormats = formats,
            verifyPlugin = { null },
            httpClient = HttpClient(MockEngine { respond("bin") }),
            downloadCache = null,
        ).getOrThrow()

    @Test
    fun `installed native library is executable`() =
        runTest {
            install("so", setOf("so", "jar"))

            assertTrue(dir.resolve("p.so").canExecute())
        }

    @Test
    fun `installed jar stays non executable`() =
        runTest {
            install("jar", setOf("so", "jar"))

            assertFalse(dir.resolve("p.jar").canExecute())
        }

    @Test
    fun `scaffold writes an executable build script and returns the canonical project path`() {
        val config = TemplateConfig("my-check", "com.example.check", ModuleType.RULE)

        val project = PluginTemplateGenerator()
            .generateProject(
                "my-check",
                ModuleType.RULE,
                config,
                kotlinx.io.files.Path(dir.path)
            ).getOrThrow()

        val script = File(project.toString()).resolve("build.sh")
        assertTrue(script.canExecute(), "build.sh must be executable")
        assertTrue(script.readText().startsWith("#!/bin/sh"))
        assertEquals(dir.resolve("my-check-plugin").canonicalPath, project.toString())
    }
}
