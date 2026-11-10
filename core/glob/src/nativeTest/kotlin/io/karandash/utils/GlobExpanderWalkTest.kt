package io.karandash.utils

import io.karandash.logger.Logger
import io.karandash.platform.createDirectories
import io.karandash.platform.deleteIfExists
import io.karandash.platform.isDirectory
import io.karandash.platform.listFiles
import io.karandash.platform.writeText
import io.karandash.testfixtures.TestFiles
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.io.files.Path
import platform.posix.chmod
import platform.posix.closedir
import platform.posix.opendir
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** unreadable directories are logged; a pattern without `**` does not walk below its depth. */
@OptIn(ExperimentalForeignApi::class)
class GlobExpanderWalkTest {
    private val root = TestFiles
        .createTestTempDirectory("glob-walk")
        .also { createDirectories(it) }
    private val warnings = mutableListOf<String>()
    private val logger = object : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }
    private val expander = GlobExpander(baseDir = root, logger = logger)

    @AfterTest
    fun cleanup() = deleteTree(root)

    private fun deleteTree(path: Path) {
        chmod(path.toString(), OPEN_MODE.convert())
        if (path.isDirectory()) path.listFiles().orEmpty().forEach { deleteTree(it) }
        deleteIfExists(path)
    }

    /** Locks [name]; false when the lock does not hold (root, as in the CI container, still reads it). */
    private fun lockedDir(name: String): Boolean {
        val dir = Path(root, name)
        createDirectories(dir)
        chmod(dir.toString(), 0.convert())
        val handle = opendir(dir.toString()) ?: return true
        closedir(handle)
        return false
    }

    @Test
    fun `unreadable directory is logged during a double star walk`() {
        Path(root, "a.yaml").writeText("x")
        if (!lockedDir("locked")) return

        assertEquals(listOf("a.yaml"), expander.expand("**/*.yaml"))
        assertTrue(warnings.any { "locked" in it }, warnings.toString())
    }

    @Test
    fun `pattern without double star does not walk below its depth`() {
        Path(root, "a.yaml").writeText("x")
        lockedDir("deep")

        assertEquals(listOf("a.yaml"), expander.expand("*.yaml"))
        assertTrue(warnings.isEmpty(), warnings.toString())
    }

    private companion object {
        const val OPEN_MODE = 0x1C0
    }
}
