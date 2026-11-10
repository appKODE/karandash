package io.karandash.utils

import kotlin.test.Test
import kotlin.test.assertTrue

class GlobMatcherSpecificTest {
    @Test
    fun `test patterns from FileChangesValidatorTest`() {
        // Pattern 1: *.md should match README.md
        assertTrue(GlobMatcher.matches("README.md", "*.md"), "*.md should match README.md")

        // Pattern 2: src/**/*.kt should match src/main.kt
        assertTrue(GlobMatcher.matches("src/main.kt", "src/**/*.kt"), "src/**/*.kt should match src/main.kt")

        // Pattern 3: database/** should match database/schema.sql
        assertTrue(GlobMatcher.matches("database/schema.sql", "database/**"), "database/** should match database/schema.sql")
    }
}
