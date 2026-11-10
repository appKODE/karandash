package io.karandash.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlobMatcherCodeownersTest {
    @Test
    fun `unanchored pattern matches at any depth`() {
        assertTrue(GlobMatcher.codeownersMatches("README.md", "*.md"))
        assertTrue(GlobMatcher.codeownersMatches("docs/guide/intro.md", "*.md"))
        assertFalse(GlobMatcher.codeownersMatches("docs/intro.txt", "*.md"))
    }

    @Test
    fun `leading slash anchors the pattern to the repository root`() {
        assertTrue(GlobMatcher.codeownersMatches("build.gradle.kts", "/build.gradle.kts"))
        assertFalse(GlobMatcher.codeownersMatches("app/build.gradle.kts", "/build.gradle.kts"))
    }

    @Test
    fun `trailing slash matches everything under the directory`() {
        assertTrue(GlobMatcher.codeownersMatches("docs/a.md", "/docs/"))
        assertTrue(GlobMatcher.codeownersMatches("docs/deep/b/c.kt", "/docs/"))
        assertFalse(GlobMatcher.codeownersMatches("src/docs/a.md", "/docs/"))
        assertTrue(GlobMatcher.codeownersMatches("src/docs/a.md", "docs/"))
    }

    @Test
    fun `a bare directory name owns the files under it`() {
        assertTrue(GlobMatcher.codeownersMatches("docs/readme.md", "docs"))
        assertTrue(GlobMatcher.codeownersMatches("app/docs/guide/a.md", "docs"))
        assertTrue(GlobMatcher.codeownersMatches("docs/readme.md", "/docs"))
        assertFalse(GlobMatcher.codeownersMatches("src/docs.kt", "docs"))
        assertFalse(GlobMatcher.codeownersMatches("app/docs/a.md", "/docs"))
    }

    @Test
    fun `leading slash on the path and whitespace around the pattern are ignored`() {
        assertTrue(GlobMatcher.codeownersMatches("/src/Main.kt", "  /src/*.kt  "))
    }

    @Test
    fun `codeowners and plain glob caches do not collide for the same pattern`() {
        assertFalse(GlobMatcher.matches("a/b.md", "*.md"))
        assertTrue(GlobMatcher.codeownersMatches("a/b.md", "*.md"))
        assertFalse(GlobMatcher.matches("a/b.md", "*.md"))
    }

    @Test
    fun `regex metacharacters in patterns are matched literally`() {
        val literals = listOf("a+b", "a^b", "a\$b", "a(b)", "a|b", "a{b}", "a[b]", "a.b", "a\\b")
        literals.forEach { name ->
            assertTrue(GlobMatcher.matches(name, name), name)
            assertFalse(GlobMatcher.matches(name.replace(name[1], 'X'), name), name)
        }
    }

    @Test
    fun `middle and trailing double star span zero or more segments`() {
        val pattern = "src/**/test/**"
        val cases = mapOf(
            "src/test" to true,
            "src/test/A.kt" to true,
            "src/a/b/test/c/D.kt" to true,
            "src/main/A.kt" to false,
            "lib/src/test/A.kt" to false,
        )
        assertEquals(cases, cases.mapValues { (path, _) -> GlobMatcher.matches(path, pattern) })
    }
}
