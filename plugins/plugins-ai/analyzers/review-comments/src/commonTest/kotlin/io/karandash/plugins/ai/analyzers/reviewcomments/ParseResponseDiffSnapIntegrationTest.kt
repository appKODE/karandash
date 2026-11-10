package io.karandash.plugins.ai.analyzers.reviewcomments

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ParseResponseDiffSnapIntegrationTest {
    private val module = ReviewCommentsAnalyzerModule()

    private fun mrWith(
        path: String,
        diffContent: String?
    ) = MergeRequest(
        id = "1",
        iid = 1,
        title = "test",
        description = null,
        sourceBranch = "feat",
        targetBranch = "main",
        author = User(username = "dev", name = "Dev"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        commits = emptyList(),
        changes = listOf(
            FileChange(
                path = path,
                changeType = ChangeType.MODIFIED,
                additions = 0,
                deletions = 0,
                diffContent = diffContent,
            ),
        ),
    )

    private fun commentJson(
        file: String,
        line: Int,
        endLine: Int? = null
    ): String {
        val endPart = if (endLine != null) """, "endLine": $endLine""" else ""
        return """{"comments": [{"file": "$file", "line": $line$endPart, "category": "style", "severity": "info", "message": "test"}]}"""
    }

    private val multiHunkDiff = """
        --- a/src/Calculator.kt
        +++ b/src/Calculator.kt
        @@ -1,6 +1,6 @@
         package com.example

        -class Calculator {
        +class Calculator(private val precision: Int = 2) {
             fun add(a: Int, b: Int): Int = a + b
             fun subtract(a: Int, b: Int): Int = a - b
         }
        @@ -15,5 +15,7 @@
         fun main() {
        +    val calc = Calculator()
             println("hello")
        +    println(calc.add(1, 2))
             println("world")
         }
    """.trimIndent()

    private val realWorldDiff = """
        --- a/src/physics/Simulation.java
        +++ b/src/physics/Simulation.java
        @@ -575,12 +575,13 @@
             double x = 0;
             double y = 0;
             double z = 0;
             double a = 0;
             double b = 0;
             double c = 0;
        +    double w = 1;
             double d = 0;
             double e = 0;
             double f = 0;
             double g = 0;
             double h = 0;
    """.trimIndent()

    private val modifiedFileDiff = """
        --- a/src/Main.kt
        +++ b/src/Main.kt
        @@ -10,9 +10,10 @@
         fun processData(input: String): String {
             val trimmed = input.trim()
        -    if (trimmed.isEmpty()) {
        -        return ""
        -    }
        +    if (trimmed.isEmpty()) return ""
        +    val validated = validate(trimmed)
             return trimmed
         }
    """.trimIndent()

    private val newFileDiff = """
        --- /dev/null
        +++ b/src/service/UserService.kt
        @@ -0,0 +1,5 @@
        +package com.example
        +
        +class UserService {
        +    fun getUser(id: String) = id
        +}
    """.trimIndent()

    private val deletedFileDiff = """
        --- a/src/service/OldService.kt
        +++ /dev/null
        @@ -1,4 +0,0 @@
        -package com.example
        -
        -class OldService {
        -}
    """.trimIndent()

    private val consecutiveAdditionsDiff = """
        @@ -5,3 +5,8 @@
         ctx5
        +add6
        +add7
        +add8
        +add9
        +add10
         ctx6
    """.trimIndent()

    @Test
    fun exact_added_line_passes_through() {
        val mr = mrWith("src/Calculator.kt", multiHunkDiff)
        val findings = module.parseResponse(commentJson("src/Calculator.kt", 3), "m", 0, mr)
        assertEquals(1, findings.size)
        assertEquals(3, (findings[0].location as Location.FileRef).line)
    }

    @Test
    fun context_line_farther_than_2_lines_is_demoted_to_file_level() {
        // realWorldDiff has a single `+` at line 581; LLM reports 584 (a context line)
        // with default maxSnap=2 the gap is too large - demote to a file-level comment rather than
        // silently relocate it by 3+ lines onto an unrelated change.
        val mr = mrWith("src/physics/Simulation.java", realWorldDiff)
        val findings = module.parseResponse(commentJson("src/physics/Simulation.java", 584), "m", 0, mr)
        assertDemotedToFileLevel(findings, "src/physics/Simulation.java", "584")
    }

    @Test
    fun line_within_snap_range_snaps_correctly() {
        val mr = mrWith("src/Main.kt", modifiedFileDiff)
        val findings = module.parseResponse(commentJson("src/Main.kt", 10), "m", 0, mr)
        assertEquals(1, findings.size)
        assertEquals(12, (findings[0].location as Location.FileRef).line)
    }

    @Test
    fun line_beyond_snap_range_is_demoted_to_file_level() {
        val mr = mrWith("src/physics/Simulation.java", realWorldDiff)
        val findings = module.parseResponse(commentJson("src/physics/Simulation.java", 200), "m", 0, mr)
        assertDemotedToFileLevel(findings, "src/physics/Simulation.java", "200")
    }

    @Test
    fun deleted_line_in_diff_snaps_to_added() {
        val mr = mrWith("src/Main.kt", modifiedFileDiff)
        val findings = module.parseResponse(commentJson("src/Main.kt", 11), "m", 0, mr)
        assertEquals(1, findings.size)
        assertEquals(12, (findings[0].location as Location.FileRef).line)
    }

    @Test
    fun deleted_file_addedLines_empty_passes_lines_through() {
        val mr = mrWith("src/service/OldService.kt", deletedFileDiff)
        val findings = module.parseResponse(commentJson("src/service/OldService.kt", 2), "m", 0, mr)
        assertEquals(1, findings.size)
        assertEquals(2, (findings[0].location as Location.FileRef).line)
    }

    @Test
    fun renamed_file_no_additions_passes_lines_through() {
        val renamedDiff = """
            --- a/src/old/Name.kt
            +++ b/src/new/Name.kt
            @@ -1,5 +1,5 @@
             package com.example

             class Name {
                 val value = ""
             }
        """.trimIndent()
        val mr = mrWith("src/new/Name.kt", renamedDiff)
        val findings = module.parseResponse(commentJson("src/new/Name.kt", 3), "m", 0, mr)
        assertEquals(1, findings.size)
        assertEquals(3, (findings[0].location as Location.FileRef).line)
    }

    @Test
    fun null_diff_passes_lines_through() {
        val mr = mrWith("src/Main.kt", null)
        val findings = module.parseResponse(commentJson("src/Main.kt", 99), "m", 0, mr)
        assertEquals(1, findings.size)
        assertEquals(99, (findings[0].location as Location.FileRef).line)
    }

    @Test
    fun no_mr_context_passes_lines_through() {
        val findings = module.parseResponse(commentJson("src/Main.kt", 42), "m", 0, mr = null)
        assertEquals(1, findings.size)
        assertEquals(42, (findings[0].location as Location.FileRef).line)
    }

    @Test
    fun endLine_snaps_independently() {
        // multiHunkDiff addedLines = {3, 16, 18}
        // endLine=17: delta=1 → 18 is in set (forward wins) → snaps to 18
        val mr = mrWith("src/Calculator.kt", multiHunkDiff)
        val json = commentJson("src/Calculator.kt", 3, endLine = 17)
        val findings = module.parseResponse(json, "m", 0, mr)
        assertEquals(1, findings.size)
        val loc = findings[0].location as Location.FileRef
        assertEquals(3, loc.line)
        assertEquals(18, loc.endLine)
    }

    @Test
    fun endLine_beyond_snap_becomes_null() {
        val mr = mrWith("src/physics/Simulation.java", realWorldDiff)
        val json = commentJson("src/physics/Simulation.java", 581, endLine = 600)
        val findings = module.parseResponse(json, "m", 0, mr)
        assertEquals(1, findings.size)
        val loc = findings[0].location as Location.FileRef
        assertEquals(581, loc.line)
        assertNull(loc.endLine)
    }

    @Test
    fun new_file_all_lines_are_valid() {
        val mr = mrWith("src/service/UserService.kt", newFileDiff)
        val findings = module.parseResponse(commentJson("src/service/UserService.kt", 3), "m", 0, mr)
        assertEquals(1, findings.size)
        assertEquals(3, (findings[0].location as Location.FileRef).line)
    }

    @Test
    fun consecutive_addition_block_snaps_to_first_added() {
        val mr = mrWith("src/file.kt", consecutiveAdditionsDiff)
        val findings = module.parseResponse(commentJson("src/file.kt", 5), "m", 0, mr)
        assertEquals(1, findings.size)
        assertEquals(6, (findings[0].location as Location.FileRef).line)
    }

    private fun assertDemotedToFileLevel(
        findings: List<Finding>,
        path: String,
        llmLine: String
    ) {
        assertEquals(1, findings.size)
        assertEquals(Location.FileRef(path = path), findings[0].location)
        assertEquals("true", findings[0].metadata[AiFindingMetadataKeys.LINE_DEMOTED])
        assertEquals(llmLine, findings[0].metadata[AiFindingMetadataKeys.LLM_LINE])
    }
}
