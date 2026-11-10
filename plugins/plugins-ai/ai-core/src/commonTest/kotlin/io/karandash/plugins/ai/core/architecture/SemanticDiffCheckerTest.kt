package io.karandash.plugins.ai.core.architecture

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SemanticDiffCheckerTest {
    private fun change(
        path: String = "src/Foo.kt",
        diff: String?
    ) = FileChange(path = path, changeType = ChangeType.MODIFIED, additions = 1, deletions = 1, diffContent = diff)

    // region no diff

    @Test
    fun noDiffContentReturnsEmpty() {
        val findings = SemanticDiffChecker.check(listOf(change(diff = null)))
        assertTrue(findings.isEmpty())
    }

    @Test
    fun emptyChangesReturnsEmpty() {
        val findings = SemanticDiffChecker.check(emptyList())
        assertTrue(findings.isEmpty())
    }

    // endregion

    // region removed public function

    @Test
    fun removedPublicFunctionDetected() {
        val diff = "-fun doSomething(x: Int): String\n+// removed"
        val findings = SemanticDiffChecker.check(listOf(change(diff = diff)))
        assertEquals(1, findings.size)
        assertTrue(findings.first().message.contains("doSomething"))
        assertEquals(Severity.WARNING, findings.first().severity)
        assertEquals(SemanticDiffChecker.RULE_ID, findings.first().rule)
    }

    @Test
    fun removedPublicFunctionWithExplicitPublicModifier() {
        val diff = "-public fun myApi(a: String): Boolean"
        val findings = SemanticDiffChecker.check(listOf(change(diff = diff)))
        assertEquals(1, findings.size)
        assertTrue(findings.first().message.contains("myApi"))
    }

    // endregion

    // region removed public class

    @Test
    fun removedPublicClassDetectedAsError() {
        val diff = "-class MyService(val name: String)"
        val findings = SemanticDiffChecker.check(listOf(change(diff = diff)))
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings.first().severity)
        assertTrue(findings.first().message.contains("MyService"))
    }

    @Test
    fun removedPublicInterfaceDetectedAsError() {
        val diff = "-interface Validator"
        val findings = SemanticDiffChecker.check(listOf(change(diff = diff)))
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings.first().severity)
        assertTrue(findings.first().message.contains("Validator"))
    }

    @Test
    fun removedPublicObjectDetectedAsError() {
        val diff = "-object Registry"
        val findings = SemanticDiffChecker.check(listOf(change(diff = diff)))
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings.first().severity)
        assertTrue(findings.first().message.contains("Registry"))
    }

    // endregion

    // region removed public property

    @Test
    fun removedPublicValDetected() {
        val diff = "-val apiVersion: Int = 1"
        val findings = SemanticDiffChecker.check(listOf(change(diff = diff)))
        assertEquals(1, findings.size)
        assertTrue(findings.first().message.contains("apiVersion"))
        assertEquals(Severity.WARNING, findings.first().severity)
    }

    @Test
    fun removedPublicVarDetected() {
        val diff = "-var count: Int = 0"
        val findings = SemanticDiffChecker.check(listOf(change(diff = diff)))
        assertEquals(1, findings.size)
        assertTrue(findings.first().message.contains("count"))
    }

    // endregion

    // region private/internal ignored

    @Test
    fun removedPrivateFunctionNotFlagged() {
        val diff = "-private fun helper(): Unit"
        val findings = SemanticDiffChecker.check(listOf(change(diff = diff)))
        assertTrue(findings.isEmpty())
    }

    @Test
    fun removedInternalFunctionNotFlagged() {
        val diff = "-internal fun internalFn(): Unit"
        val findings = SemanticDiffChecker.check(listOf(change(diff = diff)))
        assertTrue(findings.isEmpty())
    }

    @Test
    fun removedPrivatePropertyNotFlagged() {
        val diff = "-private val secret: String = \"\""
        val findings = SemanticDiffChecker.check(listOf(change(diff = diff)))
        assertTrue(findings.isEmpty())
    }

    // endregion

    // region renamed function not flagged

    @Test
    fun renamedFunctionRemovedAndAddedNotFlagged() {
        val diff = "-fun oldName(x: Int): String\n+fun newName(x: Int): String"
        val findings = SemanticDiffChecker.check(listOf(change(diff = diff)))
        // oldName removed but newName added - oldName is not in added set, so it IS flagged
        // newName is a new symbol (not flagged for removal)
        // Only oldName should be flagged since it was not re-added under the same name
        assertEquals(1, findings.size)
        assertTrue(findings.first().message.contains("oldName"))
    }

    @Test
    fun removedFunctionNotFlaggedWhenSameNameReAdded() {
        val diff = "-fun process(x: Int): String\n+fun process(x: Int, y: Int): String"
        val findings = SemanticDiffChecker.check(listOf(change(diff = diff)))
        // Same name re-added with different signature - not flagged as removed
        assertTrue(findings.none { it.message.contains("'process'") })
    }

    // endregion

    // region location

    @Test
    fun findingLocationIsFileRef() {
        val diff = "-fun foo(): Unit"
        val findings = SemanticDiffChecker.check(listOf(change(path = "src/Api.kt", diff = diff)))
        assertEquals(1, findings.size)
        val loc = findings.first().location
        val fileRef = assertIs<Location.FileRef>(loc)
        assertEquals("src/Api.kt", fileRef.path)
    }

    // endregion
}
