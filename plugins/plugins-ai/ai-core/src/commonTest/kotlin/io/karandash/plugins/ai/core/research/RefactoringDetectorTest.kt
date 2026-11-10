package io.karandash.plugins.ai.core.research

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RefactoringDetectorTest {
    private fun change(
        path: String,
        type: ChangeType = ChangeType.MODIFIED,
        diff: String? = null,
    ) = FileChange(path = path, changeType = type, diffContent = diff)

    @Test
    fun `import-only changes detected as IMPORT_REORG`() {
        val diff = """
            --- a/Foo.kt
            +++ b/Foo.kt
            -import com.example.Old
            +import com.example.New
        """.trimIndent()
        val analysis = RefactoringDetector.analyze(listOf(change("Foo.kt", diff = diff)))
        assertEquals(1, analysis.refactoringPatterns.size)
        assertEquals(RefactoringType.IMPORT_REORG, analysis.refactoringPatterns[0].type)
        assertTrue(analysis.behavioralChanges.isEmpty())
    }

    @Test
    fun `formatting-only detected as FORMATTING`() {
        val diff = """
            --- a/Bar.kt
            +++ b/Bar.kt
            -
            +
        """.trimIndent()
        val analysis = RefactoringDetector.analyze(listOf(change("Bar.kt", diff = diff)))
        assertEquals(1, analysis.refactoringPatterns.size)
        assertEquals(RefactoringType.FORMATTING, analysis.refactoringPatterns[0].type)
    }

    @Test
    fun `move class detected when deleted file has same name as added file`() {
        val deleted = change("old/pkg/Service.kt", type = ChangeType.DELETED, diff = "-class Service {}")
        val added = change("new/pkg/Service.kt", type = ChangeType.ADDED, diff = "+class Service {}")
        val analysis = RefactoringDetector.analyze(listOf(deleted, added))
        val movePattern = analysis.refactoringPatterns.find { it.type == RefactoringType.MOVE_CLASS }
        assertNotNull(movePattern, "Expected MOVE_CLASS pattern")
        assertEquals("old/pkg/Service.kt", movePattern.file)
    }

    @Test
    fun `behavioral change not classified as refactoring`() {
        val diff = """
            --- a/Logic.kt
            +++ b/Logic.kt
            -fun old() = 1
            +fun new() = 2
        """.trimIndent()
        val analysis = RefactoringDetector.analyze(listOf(change("Logic.kt", diff = diff)))
        assertTrue(analysis.refactoringPatterns.isEmpty())
        assertEquals(1, analysis.behavioralChanges.size)
    }

    @Test
    fun `mixed changes classified correctly`() {
        val importOnlyDiff = "-import com.a.Old\n+import com.a.New"
        val behavioralDiff = "-fun old() = 1\n+fun new() = 2"
        val changes = listOf(
            change("Imports.kt", diff = importOnlyDiff),
            change("Logic.kt", diff = behavioralDiff),
        )
        val analysis = RefactoringDetector.analyze(changes)
        assertTrue(analysis.isMixed)
        assertFalse(analysis.isRefactoringOnly)
        assertEquals(1, analysis.refactoringPatterns.size)
        assertEquals(1, analysis.behavioralChanges.size)
    }

    @Test
    fun `isRefactoringOnly true when all changes are refactoring`() {
        val importDiff = "-import com.a.Old\n+import com.a.New"
        val analysis = RefactoringDetector.analyze(
            listOf(
                change("A.kt", diff = importDiff),
                change("B.kt", diff = importDiff),
            ),
        )
        assertTrue(analysis.isRefactoringOnly)
        assertFalse(analysis.isMixed)
    }

    @Test
    fun `empty changes returns empty analysis`() {
        val analysis = RefactoringDetector.analyze(emptyList())
        assertTrue(analysis.refactoringPatterns.isEmpty())
        assertTrue(analysis.behavioralChanges.isEmpty())
        assertFalse(analysis.isRefactoringOnly)
        assertFalse(analysis.isMixed)
        assertEquals(0.0, analysis.refactoringRatio)
    }

    @Test
    fun `null diff treated as behavioral change`() {
        val analysis = RefactoringDetector.analyze(listOf(change("Unknown.kt", diff = null)))
        assertEquals(1, analysis.behavioralChanges.size)
        assertTrue(analysis.refactoringPatterns.isEmpty())
    }

    @Test
    fun `refactoringRatio computed correctly`() {
        val importDiff = "-import com.a.Old\n+import com.a.New"
        val behavioralDiff = "-fun old() = 1\n+fun new() = 2"
        val analysis = RefactoringDetector.analyze(
            listOf(
                change("A.kt", diff = importDiff),
                change("B.kt", diff = behavioralDiff),
            ),
        )
        assertEquals(0.5, analysis.refactoringRatio)
    }
}
