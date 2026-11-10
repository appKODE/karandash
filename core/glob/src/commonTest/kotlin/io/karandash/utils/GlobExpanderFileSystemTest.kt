package io.karandash.utils

import io.karandash.api.errors.ConfigException
import io.karandash.api.errors.KarandashError
import io.karandash.platform.createDirectories
import io.karandash.platform.deleteIfExists
import io.karandash.platform.isDirectory
import io.karandash.platform.listFiles
import io.karandash.platform.writeText
import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GlobExpanderFileSystemTest {
    private val root = TestFiles
        .createTestTempDirectory("glob-expander-test")
        .also { createDirectories(it) }

    private val expander = GlobExpander(baseDir = root)

    @AfterTest
    fun cleanup() = deleteTree(root)

    private fun deleteTree(path: Path) {
        if (path.isDirectory()) path.listFiles().orEmpty().forEach { deleteTree(it) }
        deleteIfExists(path)
    }

    private fun file(relative: String) {
        val path = Path(root, relative)
        path.parent?.let { createDirectories(it) }
        path.writeText("x")
    }

    @Test
    fun `double star matches files at every depth in sorted order`() {
        listOf("b.kt", "a.kt", "src/main/Z.kt", "src/c.kt", "src/readme.md").forEach(::file)

        assertEquals(listOf("a.kt", "b.kt", "src/c.kt", "src/main/Z.kt"), expander.expand("**/*.kt"))
    }

    @Test
    fun `single star does not cross directory boundaries`() {
        listOf("src/a.kt", "src/sub/b.kt").forEach(::file)

        assertEquals(listOf("src/a.kt"), expander.expand("src/*.kt"))
    }

    @Test
    fun `brace alternatives are unioned without duplicates`() {
        listOf("a.yaml", "b.yaml", "c.yaml").forEach(::file)

        assertEquals(listOf("a.yaml", "b.yaml"), expander.expand("{a,b,a}.yaml"))
        assertEquals(listOf("a.yaml", "b.yaml", "c.yaml"), expander.expand("{*.yaml,a.yaml}"))
    }

    @Test
    fun `literal path without wildcard matches only that file`() {
        listOf("conf/base.yaml", "conf/other.yaml").forEach(::file)

        assertEquals(listOf("conf/base.yaml"), expander.expand("conf/base.yaml"))
    }

    @Test
    fun `question mark matches exactly one character`() {
        listOf("a1.txt", "a2.txt", "a10.txt", "b1.txt").forEach(::file)

        assertEquals(listOf("a1.txt", "a2.txt"), expander.expand("a?.txt"))
    }

    @Test
    fun `square brackets are literal locally`() {
        listOf("a1.txt", "[ab]1.txt").forEach(::file)

        assertEquals(listOf("[ab]1.txt"), expander.expand("[ab]1.txt"))
    }

    @Test
    fun `pattern with no matching file returns an empty list`() {
        file("a.kt")

        assertEquals(emptyList(), expander.expand("*.java"))
    }

    @Test
    fun `missing search root fails with the expansion error code`() {
        val error = assertFailsWith<ConfigException> { expander.expand("missing/*.yaml") }

        assertTrue(error.message.orEmpty().contains(KarandashError.GLOB_EXPANSION_FAILED.bracketCode))
        assertTrue(error.message.orEmpty().contains("'missing' does not exist"))
    }

    @Test
    fun `remote url patterns are rejected for every scheme`() {
        listOf("http://h/a.yaml", "https://h/a.yaml", "ftp://h/a.yaml", "git://h/a.yaml").forEach { pattern ->
            val error = assertFailsWith<ConfigException> { expander.expand(pattern) }
            assertTrue(error.message.orEmpty().contains("remote URL"), pattern)
        }
    }

    @Test
    fun `parent segments are rejected even when produced by braces`() {
        listOf("../*.yaml", "a/../b.yaml", "{a,..}/x.yaml", "..\\x.yaml").forEach { pattern ->
            val error = assertFailsWith<ConfigException> { expander.expand(pattern) }
            assertTrue(error.message.orEmpty().contains(KarandashError.GLOB_SECURITY_VIOLATION.bracketCode), pattern)
        }
    }

    @Test
    fun `double dot inside a file name is not traversal`() {
        file("v1..2.yaml")

        assertEquals(listOf("v1..2.yaml"), expander.expand("v1..2.yaml"))
    }

    @Test
    fun `more matches than the limit fail with the expansion error code`() {
        (1..4).forEach { file("f$it.txt") }
        val limited = expander.withLimits(maxExpansion = 3, maxDirectoryDepth = 10)

        val error = assertFailsWith<ConfigException> { limited.expand("*.txt") }

        assertTrue(error.message.orEmpty().contains("max: 3"))
        assertEquals(3, expander.withLimits(maxExpansion = 3, maxDirectoryDepth = 10).expand("f{1,2,3}.txt").size)
    }

    @Test
    fun `brace expansion over the limit becomes a config exception`() {
        val limited = expander.withLimits(maxExpansion = 2, maxDirectoryDepth = 10)

        val error = assertFailsWith<ConfigException> { limited.expand("{a,b,c}.yaml") }

        assertTrue(error.message.orEmpty().contains(KarandashError.GLOB_EXPANSION_FAILED.bracketCode))
    }

    @Test
    fun `files below the directory depth limit are not walked`() {
        listOf("top.kt", "d1/one.kt", "d1/d2/two.kt").forEach(::file)

        assertEquals(listOf("d1/one.kt", "top.kt"), expander.withLimits(100, maxDirectoryDepth = 1).expand("**/*.kt"))
        assertEquals(listOf("top.kt"), expander.withLimits(100, maxDirectoryDepth = 0).expand("**/*.kt"))
    }

    @Test
    fun `withBaseDir resolves relative to the new directory`() {
        listOf("child/a.yaml", "b.yaml").forEach(::file)

        assertEquals(listOf("a.yaml"), expander.withBaseDir(Path(root, "child")).expand("*.yaml"))
    }

    @Test
    fun `expandRemote expands braces only and keeps first occurrence order`() {
        assertEquals(listOf("b.yaml", "a.yaml"), expander.expandRemote("{b,a,b}.yaml"))
        assertEquals(listOf("dir/plain.yaml"), expander.expandRemote("dir/plain.yaml"))
    }

    @Test
    fun `expandRemote rejects wildcards left after brace expansion`() {
        listOf("*.yaml", "{a,b?}.yaml", "[ab].yaml").forEach { pattern ->
            val error = assertFailsWith<ConfigException> { expander.expandRemote(pattern) }
            assertTrue(error.message.orEmpty().contains("remote config"), pattern)
        }
        assertFailsWith<ConfigException> { expander.expandRemote("../a.yaml") }
    }

    @Test
    fun `unbalanced braces are kept literally`() {
        assertEquals(listOf("{a,b"), GlobExpander.expandBraces("{a,b"))
        assertEquals(listOf("a}b"), GlobExpander.expandBraces("a}b"))
        assertEquals(listOf("x{a,b"), expander.expandRemote("x{a,b"))
    }

    @Test
    fun `brace expansion count matches materialized size for random patterns`() {
        val random = Random(42)
        repeat(200) {
            val groups = random.nextInt(1, 4)
            val sizes = List(groups) { random.nextInt(1, 4) }
            val pattern = sizes.withIndex().joinToString("/") { (g, n) -> (1..n).joinToString(",", "{", "}") { "g${g}a$it" } }
            val expected = sizes.fold(1) { acc, n -> acc * n }

            val result = GlobExpander.expandBraces(pattern)

            assertEquals(expected, result.size, pattern)
            assertEquals(expected, result.distinct().size, pattern)
            assertTrue(result.none { '{' in it || '}' in it || ',' in it }, pattern)
            if (expected > 1) assertFailsWith<IllegalArgumentException> { GlobExpander.expandBraces(pattern, expected - 1) }
        }
    }
}
