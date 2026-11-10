package io.karandash.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/** [PathSecurity.normalizeLexical] against the copies it replaced, on inputs without `\` or a relative leading `..`. */
class NormalizeLexicalTest {
    private val inputs = listOf(
        "",
        "/",
        "a",
        "/a/b/../c",
        "./config.yaml",
        "configs/./config.yaml",
        "dir/../config.yaml",
        "/../../etc",
        "a//b/",
        "/x/./y/../../z",
        "C:/x/../y",
        "/C:/x",
        "a/b/../../c",
    )

    @Test
    fun `normalizePath matches the old URL path normalizer`() {
        inputs.forEach { assertEquals(legacyUrlPath(it), PathSecurity.normalizePath(it), it) }
    }

    @Test
    fun `normalizeLexical matches the old config chain normalizer`() {
        inputs.forEach { assertEquals(legacyChainPath(it), PathSecurity.normalizeLexical(it), it) }
    }

    @Test
    fun `normalizeLexical keeps a parent above a relative start and unifies backslashes`() {
        assertEquals("../a", PathSecurity.normalizeLexical("../a"))
        assertEquals("../../b", PathSecurity.normalizeLexical("x/../../../b"))
        assertEquals("C:/y", PathSecurity.normalizeLexical("C:\\x\\..\\y"))
        assertEquals("/etc", PathSecurity.normalizePath("/a/..\\..\\etc"))
    }

    private fun legacyUrlPath(path: String): String = "/" + collapse(path).joinToString("/")

    private fun legacyChainPath(path: String): String = (if (path.startsWith("/")) "/" else "") + collapse(path).joinToString("/")

    private fun collapse(path: String): List<String> {
        val result = mutableListOf<String>()
        path.split("/").forEach { seg ->
            when (seg) {
                ".", "" -> Unit
                ".." -> if (result.isNotEmpty()) result.removeLast()
                else -> result.add(seg)
            }
        }
        return result
    }
}
