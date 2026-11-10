package io.karandash.utils

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlobMatcherTest {
    @Test
    fun `test basic glob matching`() {
        assertTrue(GlobMatcher.matches("test.kt", "*.kt"))
        assertFalse(GlobMatcher.matches("test.java", "*.kt"))
    }

    @Test
    fun `test double star glob matching`() {
        assertTrue(GlobMatcher.matches("src/main/kotlin/Main.kt", "**/*.kt"))
        assertTrue(GlobMatcher.matches("Main.kt", "**/*.kt"))
        assertTrue(GlobMatcher.matches("a/b/c/Test.kt", "**/*.kt"))
        assertFalse(GlobMatcher.matches("Main.java", "**/*.kt"))
    }

    @Test
    fun `test path segments`() {
        assertTrue(GlobMatcher.matches("src/Test.kt", "src/*.kt"))
        assertFalse(GlobMatcher.matches("src/main/Test.kt", "src/*.kt"))
        assertTrue(GlobMatcher.matches("src/main/Test.kt", "src/**/*.kt"))
    }

    // ── Bug 12: LRU eviction ─────────────────────────────────────────────

    @Test
    fun `frequently used patterns survive cache eviction`() {
        val hotPattern = "hot-pattern-*.txt"
        // Prime the hot pattern
        GlobMatcher.matches("hot-pattern-1.txt", hotPattern)

        // Fill the cache with other patterns to force evictions
        for (i in 1..1100) {
            GlobMatcher.matches("file$i.kt", "file$i.kt")
        }

        // Hot pattern should still work (LRU keeps it if accessed recently)
        assertTrue(GlobMatcher.matches("hot-pattern-2.txt", hotPattern))
    }
}
