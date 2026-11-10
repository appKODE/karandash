package io.karandash.plugins.ai.core.architecture

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ADR affected paths use the shared anchored GlobMatcher. */
class AdrRegressionTest {
    @Test
    fun `star does not match a longer extension`() {
        assertFalse(AdrValidator.matchesGlob("src/Foo.kts", "src/*.kt"))
    }

    @Test
    fun `pattern is anchored to the path start`() {
        assertFalse(AdrValidator.matchesGlob("module/src/Foo.kt", "src/*.kt"))
    }

    @Test
    fun `double star matches nested paths`() {
        assertTrue(AdrValidator.matchesGlob("src/a/b/Foo.kt", "src/**"))
    }
}
