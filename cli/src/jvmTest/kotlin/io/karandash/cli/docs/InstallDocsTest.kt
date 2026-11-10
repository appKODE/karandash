package io.karandash.cli.docs

import io.karandash.cli.TestFileHelpers
import io.karandash.cli.compat.CompatLoader
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Install channels in the docs are the GitHub release and Docker Hub image; the README sample loads. */
class InstallDocsTest {
    private val installDocs = listOf("README.md", "docs/getting-started/installation.md")

    @Test
    fun `install docs name no channel that does not exist`() {
        installDocs.forEach { file ->
            val text = DocsSupport.read(file)
            listOf("brew install karandash", "github.com/karandash", "karandash/cli").forEach {
                assertFalse(text.contains(it), "$file still promises '$it'")
            }
        }
    }

    @Test
    fun `install docs point at the GitHub release and the Docker Hub image the release job publishes`() {
        installDocs.forEach { file ->
            val text = DocsSupport.read(file)
            listOf("github.com/appKODE/karandash/releases/download/", "SHA256SUMS", "rinekri/karandash:v").forEach {
                assertContains(text, it, message = file)
            }
            listOf("packages/generic", "PRIVATE-TOKEN").forEach {
                assertFalse(text.contains(it), "$file still points at the internal GitLab ('$it')")
            }
        }
    }

    @Test
    fun `versions in install snippets are the build version`() {
        val build = Regex("(?m)^\\s*version = \"(\\d+\\.\\d+\\.\\d+)\"").find(DocsSupport.read("build.gradle.kts"))?.groupValues?.get(1)
        assertTrue(build != null, "build version not found in build.gradle.kts")
        installDocs.forEach { file ->
            val versions = Regex("\\bv(\\d+\\.\\d+\\.\\d+)\\b").findAll(DocsSupport.read(file)).map { it.groupValues[1] }.toSet()
            assertTrue(versions.isNotEmpty(), "$file shows no pinned version")
            assertTrue(versions == setOf(build), "$file pins $versions, the build is $build")
        }
    }

    @Test
    fun `README YAML sample loads through the real config loader`() =
        runTest(timeout = 5.minutes) {
            val sample = DocsSupport.fencedBlocks(DocsSupport.read("README.md"), "yaml").first { it.contains("title-format") }
            val dir = TestFileHelpers.createTempDir("readme-sample")
            try {
                val file = Path(dir, ".karandash/config.yaml")
                TestFileHelpers.writeFile(file, sample)
                val snapshot = CompatLoader.load(file.toString())
                assertFalse(snapshot.effective.startsWith("ERROR"), snapshot.effective)
                assertTrue(snapshot.warnings.isEmpty(), snapshot.warnings)
            } finally {
                TestFileHelpers.deleteTempDir(dir)
            }
        }
}
