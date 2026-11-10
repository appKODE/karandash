package io.karandash.utils

import io.karandash.testfixtures.TestFiles
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.files.Path as IoPath

/** a symlink into a sibling sharing the base's name prefix escapes the base. */
class GlobExpanderContainmentTest {
    private val root: Path = java.nio.file.Paths
        .get(TestFiles.createTestTempDirectory("glob-containment").toString())
        .toRealPath()
    private val project: Path = root.resolve("proj").createDirectories()
    private val expander = GlobExpander(baseDir = IoPath(project.toString()))

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `symlink into a sibling sharing the base name prefix is skipped`() {
        val evil = root.resolve("proj-evil").createDirectories()
        evil.resolve("secret.yaml").writeText("x")
        Files.createSymbolicLink(project.resolve("link"), evil)
        project.resolve("ok.yaml").writeText("x")

        assertEquals(listOf("ok.yaml"), expander.expand("**/*.yaml"))
    }
}
