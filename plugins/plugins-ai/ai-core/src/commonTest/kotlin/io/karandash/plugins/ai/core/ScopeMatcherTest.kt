package io.karandash.plugins.ai.core

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.sdk.content.ContentSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScopeMatcherTest {
    private val androidScope = ScopeDefinition(
        name = "android",
        paths = listOf("android/**", "app/src/**/*.kt", "app/src/**/*.java"),
        instructions = ContentSource.Inline("Check Activity/Fragment lifecycle issues."),
    )

    private val iosScope = ScopeDefinition(
        name = "ios",
        paths = listOf("ios/**", "*.swift"),
        instructions = ContentSource.Inline("Check for retain cycles in closures."),
    )

    private val coreScope = ScopeDefinition(
        name = "core",
        paths = listOf("core/cpp/**", "*.cpp", "*.h"),
        instructions = ContentSource.Inline("Check memory safety."),
        bestPractices = ContentSource.Inline("Use RAII. Prefer std::unique_ptr."),
    )

    private val allScopes = listOf(androidScope, iosScope, coreScope)

    @Test
    fun `scope paths expand brace groups`() {
        val backend =
            ScopeDefinition(name = "backend", paths = listOf("{src,lib}/**/*.{kt,java}"), instructions = ContentSource.Inline("x"))

        fun matches(path: String) =
            ScopeMatcher
                .matchScopes(
                    listOf(FileChange(path, ChangeType.MODIFIED, additions = 1, deletions = 0)),
                    listOf(backend)
                ).isNotEmpty()

        assertTrue(matches("src/main/A.kt"))
        assertTrue(matches("lib/util/B.java"))
        assertTrue(!matches("src/main/C.swift"))
        assertTrue(!matches("test/D.kt"))
    }

    @Test
    fun `matches single scope from android files`() {
        val changes = listOf(
            FileChange("android/app/build.gradle.kts", ChangeType.MODIFIED, additions = 1, deletions = 0),
        )
        val matched = ScopeMatcher.matchScopes(changes, allScopes)
        assertEquals(1, matched.size)
        assertEquals("android", matched[0].name)
    }

    @Test
    fun `matches multiple scopes from mixed files`() {
        val changes = listOf(
            FileChange("android/app/MainActivity.kt", ChangeType.MODIFIED, additions = 5, deletions = 2),
            FileChange("core/cpp/engine.cpp", ChangeType.MODIFIED, additions = 10, deletions = 3),
        )
        val matched = ScopeMatcher.matchScopes(changes, allScopes)
        assertEquals(2, matched.size)
        assertEquals("android", matched[0].name)
        assertEquals("core", matched[1].name)
    }

    @Test
    fun `matches all three scopes`() {
        val changes = listOf(
            FileChange("app/src/main/App.kt", ChangeType.MODIFIED, additions = 1, deletions = 0),
            FileChange("ios/ViewController.swift", ChangeType.ADDED, additions = 20, deletions = 0),
            FileChange("core/cpp/lib.h", ChangeType.MODIFIED, additions = 3, deletions = 1),
        )
        val matched = ScopeMatcher.matchScopes(changes, allScopes)
        assertEquals(3, matched.size)
    }

    @Test
    fun `returns empty when no scopes match`() {
        val changes = listOf(
            FileChange("docs/README.md", ChangeType.MODIFIED, additions = 1, deletions = 0),
        )
        val matched = ScopeMatcher.matchScopes(changes, allScopes)
        assertTrue(matched.isEmpty())
    }

    @Test
    fun `returns empty for empty changes`() {
        val matched = ScopeMatcher.matchScopes(emptyList(), allScopes)
        assertTrue(matched.isEmpty())
    }

    @Test
    fun `returns empty for empty scopes`() {
        val changes = listOf(
            FileChange("android/app/Main.kt", ChangeType.MODIFIED, additions = 1, deletions = 0),
        )
        val matched = ScopeMatcher.matchScopes(changes, emptyList())
        assertTrue(matched.isEmpty())
    }

    @Test
    fun `preserves config order in matched output`() {
        val changes = listOf(
            FileChange("core/cpp/engine.cpp", ChangeType.MODIFIED, additions = 1, deletions = 0),
            FileChange("android/app/Main.kt", ChangeType.MODIFIED, additions = 1, deletions = 0),
        )
        val matched = ScopeMatcher.matchScopes(changes, allScopes)
        assertEquals(listOf("android", "core"), matched.map { it.name })
    }

    @Test
    fun `buildScopeInstructions combines instructions with headers`() {
        val matched = listOf(androidScope, coreScope)
        val result = ScopeMatcher.buildScopeInstructions(matched)!!
        assertTrue(result.contains("## Scope: android"))
        assertTrue(result.contains("Check Activity/Fragment lifecycle issues."))
        assertTrue(result.contains("## Scope: core"))
        assertTrue(result.contains("Check memory safety."))
        assertTrue(result.contains("Best practices:\nUse RAII. Prefer std::unique_ptr."))
    }

    @Test
    fun `buildScopeInstructions returns null for empty list`() {
        assertNull(ScopeMatcher.buildScopeInstructions(emptyList()))
    }

    @Test
    fun `buildScopeInstructions skips scopes with no content`() {
        val emptyScope = ScopeDefinition(name = "empty", paths = listOf("**"))
        val result = ScopeMatcher.buildScopeInstructions(listOf(emptyScope))
        assertNull(result)
    }

    @Test
    fun `buildScopeInstructions with only bestPractices`() {
        val scope = ScopeDefinition(
            name = "test",
            paths = listOf("**"),
            bestPractices = ContentSource.Inline("Follow conventions."),
        )
        val result = ScopeMatcher.buildScopeInstructions(listOf(scope))!!
        assertTrue(result.contains("## Scope: test"))
        assertTrue(result.contains("Best practices:\nFollow conventions."))
    }

    @Test
    fun `matches extension-only pattern at root`() {
        val scope = ScopeDefinition(
            name = "swift",
            paths = listOf("*.swift"),
            instructions = ContentSource.Inline("Swift review."),
        )
        val changes = listOf(
            FileChange("App.swift", ChangeType.MODIFIED, additions = 1, deletions = 0),
        )
        val matched = ScopeMatcher.matchScopes(changes, listOf(scope))
        assertEquals(1, matched.size)
    }

    @Test
    fun `matches recursive extension pattern`() {
        val scope = ScopeDefinition(
            name = "swift",
            paths = listOf("**/*.swift"),
            instructions = ContentSource.Inline("Swift review."),
        )
        val changes = listOf(
            FileChange("Sources/App.swift", ChangeType.MODIFIED, additions = 1, deletions = 0),
        )
        val matched = ScopeMatcher.matchScopes(changes, listOf(scope))
        assertEquals(1, matched.size)
    }

    /** A `glob:`-prefixed scope or instruction path (app-delta `ai.scopes.core`) matches. */
    @Test
    fun `glob-prefixed paths match`() {
        val changes = listOf(FileChange("core/cpp/map.cpp", ChangeType.MODIFIED, additions = 1, deletions = 0))
        val scope = coreScope.copy(paths = listOf("glob:core/**"))
        assertEquals(listOf("core"), ScopeMatcher.matchScopes(changes, listOf(scope)).map { it.name })
        val instruction = ReviewInstruction("cpp", listOf("glob:**/*.cpp", "!glob:**/test/**"), "t")
        assertEquals(1, ScopeMatcher.matchInstructions(changes, listOf(instruction)).size)
        val excluded = ReviewInstruction("no-core", listOf("!glob:core/**"), "t")
        assertTrue(ScopeMatcher.matchInstructions(changes, listOf(excluded)).isEmpty())
    }

    /** instruction excludes go through `TextGlobCompiler.isNegation`/`stripNegation`. */
    @Test
    fun `an excluded instruction path skips only the files it names`() {
        val instruction = ReviewInstruction("not-tests", listOf("!**/test/**"), "t")
        val inTest = listOf(FileChange("core/test/a.kt", ChangeType.MODIFIED, additions = 1, deletions = 0))
        val elsewhere = listOf(FileChange("core/main/a.kt", ChangeType.MODIFIED, additions = 1, deletions = 0))
        assertTrue(ScopeMatcher.matchInstructions(inTest, listOf(instruction)).isEmpty())
        assertEquals(1, ScopeMatcher.matchInstructions(elsewhere, listOf(instruction)).size)
    }
}
