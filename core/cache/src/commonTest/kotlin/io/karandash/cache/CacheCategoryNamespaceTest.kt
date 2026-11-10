package io.karandash.cache

import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** cache categories map to the namespaces on disk, and invalidateAll also empties the CAS namespaces. */
class CacheCategoryNamespaceTest {
    @Test
    fun `every category names the namespaces it lives in`() {
        assertEquals(listOf(CacheCategory.PROVIDER_RESPONSES), CacheCategory.forNamespace("http-responses"))
        assertEquals(listOf(CacheCategory.EXTENDS, CacheCategory.PLUGINS), CacheCategory.forNamespace("downloads"))
        assertEquals(emptyList<CacheCategory>(), CacheCategory.forNamespace("custom"))
        assertTrue(CacheCategory.entries.all { it.namespaces.isNotEmpty() })
    }

    @Test
    fun `invalidateAll removes the index entries of registered CAS namespaces`() {
        val dir = TestFiles.createTestTempDirectory("test-invalidate-all")
        SystemFileSystem.createDirectories(dir)
        val manager = CacheManager(CacheConfig(), dir)
        val state = manager.getCasCache("state")
        state.put("a", "x".encodeToByteArray(), TTL_MS)
        state.put("b", "y".encodeToByteArray(), TTL_MS)

        assertEquals(2, manager.invalidateAll())
        assertEquals(emptyList<String>(), state.keys())
    }

    private companion object {
        const val TTL_MS = 60_000L
    }
}
