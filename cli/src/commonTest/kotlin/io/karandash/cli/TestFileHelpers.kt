package io.karandash.cli

import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path

fun createTestTempDirectory(prefix: String): Path = TestFiles.createTestTempDirectory(prefix)

fun deleteTestDirectoryRecursively(dir: Path) = TestFiles.deleteRecursively(dir)

/** cli-local names for [TestFiles]; new tests use [TestFiles] directly. */
object TestFileHelpers {
    fun createTempDir(prefix: String): Path = TestFiles.createTestTempDirectory(prefix)

    fun deleteTempDir(dir: Path) = TestFiles.deleteRecursively(dir)

    fun exists(path: Path): Boolean = TestFiles.exists(path)

    fun writeFile(
        path: Path,
        content: String
    ) = TestFiles.writeText(path, content)

    fun readFile(path: Path): String = TestFiles.readText(path)
}
