package io.karandash.plugins.ai.core.architecture

import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrossMrDependencyAnalyzerTest {
    private fun makeMr(
        id: String,
        title: String = "MR $id",
        changes: List<FileChange> = emptyList(),
    ) = MergeRequest(
        id = id,
        iid = id.toIntOrNull() ?: 0,
        title = title,
        description = null,
        sourceBranch = "feature/$id",
        targetBranch = "main",
        author = User(username = "dev", name = "Dev"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        commits = listOf(Commit("sha1", "init", CommitAuthor("Dev", "dev@test.com"), "2024-01-01T00:00:00Z")),
        changes = changes,
    )

    private fun change(
        path: String,
        type: ChangeType = ChangeType.MODIFIED
    ) = FileChange(path = path, changeType = type, additions = 1, deletions = 0)

    // region no siblings

    @Test
    fun noSiblingsReturnsEmptyAnalysis() {
        val mr = makeMr("1", changes = listOf(change("src/Foo.kt")))
        val result = CrossMrDependencyAnalyzer.analyze(mr, emptyList())
        assertTrue(result.conflicts.isEmpty())
        assertTrue(result.dependencies.isEmpty())
        assertFalse(result.hasCircularDependency)
        assertEquals(0, result.siblingCount)
    }

    // endregion

    // region file conflicts

    @Test
    fun fileConflictDetectedWhenMrsModifySameFile() {
        val current = makeMr("1", changes = listOf(change("src/Foo.kt")))
        val sibling = makeMr("2", title = "Sibling", changes = listOf(change("src/Foo.kt")))
        val result = CrossMrDependencyAnalyzer.analyze(current, listOf(sibling))
        assertEquals(1, result.conflicts.size)
        val conflict = result.conflicts.first()
        assertEquals("1", conflict.mrIdA)
        assertEquals("2", conflict.mrIdB)
        assertEquals("Sibling", conflict.mrTitleB)
        assertTrue(conflict.conflictingFiles.contains("src/Foo.kt"))
    }

    @Test
    fun noConflictWhenMrsModifyDifferentFiles() {
        val current = makeMr("1", changes = listOf(change("src/Foo.kt")))
        val sibling = makeMr("2", changes = listOf(change("src/Bar.kt")))
        val result = CrossMrDependencyAnalyzer.analyze(current, listOf(sibling))
        assertTrue(result.conflicts.isEmpty())
    }

    // endregion

    // region dependencies

    @Test
    fun dependencyDetectedWhenCurrentDeletesFileSiblingModifies() {
        val current = makeMr("1", changes = listOf(change("src/Old.kt", ChangeType.DELETED)))
        val sibling = makeMr("2", title = "Uses Old", changes = listOf(change("src/Old.kt")))
        val result = CrossMrDependencyAnalyzer.analyze(current, listOf(sibling))
        assertEquals(1, result.dependencies.size)
        val dep = result.dependencies.first()
        assertEquals("1", dep.fromMrId)
        assertEquals("2", dep.toMrId)
        assertTrue(dep.affectedFiles.contains("src/Old.kt"))
    }

    @Test
    fun dependencyDetectedWhenCurrentRenamesFileSiblingModifies() {
        val current = makeMr("1", changes = listOf(change("src/Old.kt", ChangeType.RENAMED)))
        val sibling = makeMr("2", changes = listOf(change("src/Old.kt")))
        val result = CrossMrDependencyAnalyzer.analyze(current, listOf(sibling))
        assertEquals(1, result.dependencies.size)
    }

    @Test
    fun noDependencyWhenModifiedFileNotDeletedOrRenamed() {
        val current = makeMr("1", changes = listOf(change("src/Foo.kt", ChangeType.MODIFIED)))
        val sibling = makeMr("2", changes = listOf(change("src/Foo.kt")))
        val result = CrossMrDependencyAnalyzer.analyze(current, listOf(sibling))
        assertTrue(result.dependencies.isEmpty())
    }

    // endregion

    // region circular dependency

    @Test
    fun circularDependencyDetected() {
        val deps = listOf(
            MrDependency(fromMrId = "1", toMrId = "2", reason = "r", affectedFiles = listOf("a.kt")),
            MrDependency(fromMrId = "2", toMrId = "1", reason = "r", affectedFiles = listOf("b.kt")),
        )
        val analysis = CrossMrAnalysis(
            conflicts = emptyList(),
            dependencies = deps,
            hasCircularDependency = true,
            siblingCount = 1,
        )
        assertTrue(analysis.hasCircularDependency)
    }

    @Test
    fun noCircularDependencyForLinearChain() {
        val current = makeMr("1", changes = listOf(change("a.kt", ChangeType.DELETED)))
        val sibling = makeMr("2", changes = listOf(change("a.kt")))
        val result = CrossMrDependencyAnalyzer.analyze(current, listOf(sibling))
        assertFalse(result.hasCircularDependency)
    }

    // endregion

    // region same MR filtered

    @Test
    fun sameMrIdFilteredOutFromSiblings() {
        val mr = makeMr("1", changes = listOf(change("src/Foo.kt")))
        val result = CrossMrDependencyAnalyzer.analyze(mr, listOf(mr))
        assertTrue(result.conflicts.isEmpty())
    }

    // endregion

    // region formatReport

    @Test
    fun formatReportNoConflictsOrDependencies() {
        val analysis = CrossMrAnalysis(
            conflicts = emptyList(),
            dependencies = emptyList(),
            hasCircularDependency = false,
            siblingCount = 3,
        )
        val report = CrossMrDependencyAnalyzer.formatReport(analysis)
        assertTrue(report.contains("No conflicts or dependencies detected."))
        assertTrue(report.contains("Sibling MRs: 3"))
    }

    @Test
    fun formatReportIncludesConflictAndDependency() {
        val analysis = CrossMrAnalysis(
            conflicts = listOf(FileConflict("1", "2", "Feature B", listOf("src/Foo.kt"))),
            dependencies = listOf(MrDependency("1", "2", "deletes file", listOf("src/Foo.kt"))),
            hasCircularDependency = false,
            siblingCount = 1,
        )
        val report = CrossMrDependencyAnalyzer.formatReport(analysis)
        assertTrue(report.contains("File Conflicts"))
        assertTrue(report.contains("Feature B"))
        assertTrue(report.contains("Dependencies"))
        assertTrue(report.contains("deletes file"))
    }

    @Test
    fun formatReportIncludesCircularWarning() {
        val analysis = CrossMrAnalysis(
            conflicts = emptyList(),
            dependencies = listOf(MrDependency("1", "2", "reason", listOf("f.kt"))),
            hasCircularDependency = true,
            siblingCount = 1,
        )
        val report = CrossMrDependencyAnalyzer.formatReport(analysis)
        assertTrue(report.contains("Circular dependency detected"))
    }

    // endregion
}
