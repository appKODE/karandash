package io.karandash.plugins.ai.core

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LanguageDetectorTest {
    @Test
    fun `detects kotlin from kt files`() {
        val changes = listOf(
            FileChange("src/Main.kt", ChangeType.MODIFIED, additions = 10, deletions = 5),
            FileChange("build.gradle.kts", ChangeType.MODIFIED, additions = 2, deletions = 1),
        )
        assertEquals("kotlin", LanguageDetector.detect(changes))
    }

    @Test
    fun `detects python from py files`() {
        val changes = listOf(
            FileChange("app/main.py", ChangeType.MODIFIED, additions = 5, deletions = 0),
            FileChange("tests/test_main.py", ChangeType.ADDED, additions = 20, deletions = 0),
        )
        assertEquals("python", LanguageDetector.detect(changes))
    }

    @Test
    fun `detects go from go files`() {
        val changes = listOf(
            FileChange("cmd/main.go", ChangeType.MODIFIED, additions = 5, deletions = 3),
        )
        assertEquals("go", LanguageDetector.detect(changes))
    }

    @Test
    fun `detects typescript from ts and tsx files`() {
        val changes = listOf(
            FileChange("src/App.tsx", ChangeType.MODIFIED, additions = 10, deletions = 5),
            FileChange("src/utils.ts", ChangeType.MODIFIED, additions = 3, deletions = 1),
        )
        assertEquals("typescript", LanguageDetector.detect(changes))
    }

    @Test
    fun `detects java from java files`() {
        val changes = listOf(
            FileChange("src/Main.java", ChangeType.MODIFIED, additions = 5, deletions = 0),
        )
        assertEquals("java", LanguageDetector.detect(changes))
    }

    @Test
    fun `detects rust from rs files`() {
        val changes = listOf(
            FileChange("src/main.rs", ChangeType.MODIFIED, additions = 10, deletions = 5),
        )
        assertEquals("rust", LanguageDetector.detect(changes))
    }

    @Test
    fun `returns primary language by frequency`() {
        val changes = listOf(
            FileChange("src/A.kt", ChangeType.MODIFIED, additions = 1, deletions = 0),
            FileChange("src/B.py", ChangeType.MODIFIED, additions = 1, deletions = 0),
            FileChange("src/C.py", ChangeType.MODIFIED, additions = 1, deletions = 0),
            FileChange("src/D.py", ChangeType.MODIFIED, additions = 1, deletions = 0),
        )
        assertEquals("python", LanguageDetector.detect(changes))
    }

    @Test
    fun `returns null for unknown extensions`() {
        val changes = listOf(
            FileChange("README.md", ChangeType.MODIFIED, additions = 1, deletions = 0),
            FileChange("Makefile", ChangeType.MODIFIED, additions = 1, deletions = 0),
        )
        assertNull(LanguageDetector.detect(changes))
    }

    @Test
    fun `returns null for empty changes`() {
        assertNull(LanguageDetector.detect(emptyList()))
    }

    @Test
    fun `ignores files without extensions`() {
        val changes = listOf(
            FileChange("Dockerfile", ChangeType.MODIFIED, additions = 5, deletions = 0),
        )
        assertNull(LanguageDetector.detect(changes))
    }
}
