package io.karandash.platform

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PathSecurityTest {
    @Test
    fun `validateRemoteFilePath rejects a base url without a scheme whose origin shifts`() {
        val error = assertFailsWith<IllegalArgumentException> {
            PathSecurity.validateRemoteFilePath("a/b.yaml", "h")
        }

        assertTrue(error.message.orEmpty().contains("escapes origin"), error.message)
    }

    @Test
    fun `normalizePath collapses dot and parent segments`() {
        val cases = mapOf(
            "/a/b/../c" to "/a/c",
            "a/./b//c/" to "/a/b/c",
            "/../../x" to "/x",
            "" to "/",
            "/a/b/../.." to "/",
        )
        assertEquals(cases, cases.mapValues { (path, _) -> PathSecurity.normalizePath(path) })
    }

    @Test
    fun `normalized paths never contain dot segments`() {
        val random = Random(5)
        val parts = listOf("a", "b", ".", "..", "", "c.d")
        repeat(300) {
            val path = List(random.nextInt(0, 8)) { parts[random.nextInt(parts.size)] }.joinToString("/")
            val normalized = PathSecurity.normalizePath(path)

            assertTrue(normalized.startsWith("/"), path)
            assertTrue(normalized.split("/").none { it == "." || it == ".." }, path)
            assertEquals(normalized, PathSecurity.normalizePath(normalized), path)
        }
    }

    @Test
    fun `origin and path split a url at the first slash after the scheme`() {
        assertEquals("https://h.io:8080", PathSecurity.extractOrigin("https://h.io:8080/a/b"))
        assertEquals("/a/b", PathSecurity.extractPath("https://h.io:8080/a/b"))
        assertEquals("http://h.io", PathSecurity.extractOrigin("http://h.io"))
        assertEquals("", PathSecurity.extractPath("http://h.io"))
    }

    @Test
    fun `resolveRemoteUrl joins and normalizes relative paths`() {
        assertEquals("https://h/cfg/x.yaml", PathSecurity.resolveRemoteUrl("https://h/cfg", "./x.yaml"))
        assertEquals("https://h/x.yaml", PathSecurity.resolveRemoteUrl("https://h/cfg", "../x.yaml"))
        assertEquals("https://h/x.yaml", PathSecurity.resolveRemoteUrl("https://h/cfg", "../../../x.yaml"))
    }

    @Test
    fun `validateRemoteFilePath accepts files in the config dir its children and siblings`() {
        listOf("rules.yaml", "./sub/rules.yaml", "../sibling/rules.yaml").forEach { path ->
            PathSecurity.validateRemoteFilePath(path, "https://h/org/repo/dir")
        }
    }

    @Test
    fun `validateRemoteFilePath rejects absolute references and traversal`() {
        val cases = mapOf(
            "https://evil/x" to "Absolute URLs",
            "http://evil/x" to "Absolute URLs",
            "/etc/passwd" to "Absolute paths",
            "../../x.yaml" to "path traversal",
            "../../../../etc/passwd" to "path traversal",
        )
        cases.forEach { (path, message) ->
            val error = assertFailsWith<IllegalArgumentException> {
                PathSecurity.validateRemoteFilePath(path, "https://h/org/repo/dir")
            }
            assertTrue(error.message.orEmpty().contains(message), path)
        }
    }

    /** a sibling directory sharing the base's name prefix is outside the base. */
    @Test
    fun `validateRemoteFilePath rejects a sibling sharing the base name prefix`() {
        val error = assertFailsWith<IllegalArgumentException> {
            PathSecurity.validateRemoteFilePath("../../repo-evil/x.yaml", "https://h/org/repo/dir")
        }
        assertTrue(error.message.orEmpty().contains("path traversal"), error.message)
    }
}
