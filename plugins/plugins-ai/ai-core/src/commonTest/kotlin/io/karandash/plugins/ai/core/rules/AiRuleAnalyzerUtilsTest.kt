package io.karandash.plugins.ai.core.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtractAddedLinesTest {
    @Test
    fun null_diff_returns_empty_set() {
        assertTrue(AiRuleAnalyzerUtils.extractAddedLines(null).isEmpty())
    }

    @Test
    fun empty_string_returns_empty_set() {
        assertTrue(AiRuleAnalyzerUtils.extractAddedLines("").isEmpty())
    }

    @Test
    fun new_file_pure_additions() {
        val diff = """
            --- /dev/null
            +++ b/src/service/UserService.kt
            @@ -0,0 +1,5 @@
            +package com.example
            +
            +class UserService {
            +    fun getUser(id: String) = id
            +}
        """.trimIndent()
        assertEquals(setOf(1, 2, 3, 4, 5), AiRuleAnalyzerUtils.extractAddedLines(diff))
    }

    @Test
    fun deleted_file_pure_deletions() {
        val diff = """
            --- a/src/service/OldService.kt
            +++ /dev/null
            @@ -1,4 +0,0 @@
            -package com.example
            -
            -class OldService {
            -}
        """.trimIndent()
        assertTrue(AiRuleAnalyzerUtils.extractAddedLines(diff).isEmpty())
    }

    @Test
    fun modified_file_mixed_add_delete_context() {
        val diff = """
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
        assertEquals(setOf(12, 13), AiRuleAnalyzerUtils.extractAddedLines(diff))
    }

    @Test
    fun multi_hunk_diff() {
        val diff = """
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
        assertEquals(setOf(3, 16, 18), AiRuleAnalyzerUtils.extractAddedLines(diff))
    }

    @Test
    fun hunk_header_with_function_context() {
        val diff = """
            @@ -42,7 +42,8 @@ class Foo {
             fun bar() {
            -    val x = 1
            +    val x = 2
            +    val y = 3
             }
        """.trimIndent()
        assertEquals(setOf(43, 44), AiRuleAnalyzerUtils.extractAddedLines(diff))
    }

    @Test
    fun renamed_file_no_content_changes() {
        val diff = """
            --- a/src/old/Name.kt
            +++ b/src/new/Name.kt
            @@ -1,5 +1,5 @@
             package com.example

             class Name {
                 val value = ""
             }
        """.trimIndent()
        assertTrue(AiRuleAnalyzerUtils.extractAddedLines(diff).isEmpty())
    }

    @Test
    fun no_newline_at_end_of_file_marker() {
        val diff = """
            @@ -1,3 +1,4 @@
             line1
             line2
            -old3
            +new3
            +new4
            \ No newline at end of file
        """.trimIndent()
        assertEquals(setOf(3, 4), AiRuleAnalyzerUtils.extractAddedLines(diff))
    }

    @Test
    fun real_world_bug_report_sparse_addition() {
        val diff = """
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
        assertEquals(setOf(581), AiRuleAnalyzerUtils.extractAddedLines(diff))
    }

    @Test
    fun single_line_hunk_no_count() {
        val diff = """
            @@ -0,0 +1 @@
            +single added line
        """.trimIndent()
        assertEquals(setOf(1), AiRuleAnalyzerUtils.extractAddedLines(diff))
    }

    @Test
    fun multiple_consecutive_additions_then_context() {
        val diff = """
            @@ -5,3 +5,8 @@
             ctx5
            +add6
            +add7
            +add8
            +add9
            +add10
             ctx6
        """.trimIndent()
        assertEquals(setOf(6, 7, 8, 9, 10), AiRuleAnalyzerUtils.extractAddedLines(diff))
    }
}

class SnapToAddedLineTest {
    @Test
    fun exact_match_returns_line_unchanged() {
        assertEquals(5, AiRuleAnalyzerUtils.snapToAddedLine(5, setOf(3, 5, 7)))
    }

    @Test
    fun snaps_forward_when_next_is_closer() {
        assertEquals(4, AiRuleAnalyzerUtils.snapToAddedLine(3, setOf(4, 10)))
    }

    @Test
    fun snaps_backward_when_previous_is_closer() {
        assertEquals(10, AiRuleAnalyzerUtils.snapToAddedLine(11, setOf(10, 20)))
    }

    @Test
    fun prefers_forward_on_tie() {
        assertEquals(12, AiRuleAnalyzerUtils.snapToAddedLine(10, setOf(8, 12)))
    }

    @Test
    fun returns_null_when_beyond_maxSnap() {
        assertNull(AiRuleAnalyzerUtils.snapToAddedLine(10, setOf(1)))
    }

    @Test
    fun returns_null_for_empty_set() {
        assertNull(AiRuleAnalyzerUtils.snapToAddedLine(5, emptySet()))
    }

    @Test
    fun does_not_snap_by_default_outside_2_radius() {
        // Default maxSnap tightened from 5 to 2; callers that need a looser
        // radius must pass it explicitly.
        assertNull(AiRuleAnalyzerUtils.snapToAddedLine(6, setOf(1)))
    }

    @Test
    fun snaps_within_explicit_maxSnap_boundary() {
        assertEquals(1, AiRuleAnalyzerUtils.snapToAddedLine(6, setOf(1), maxSnap = 5))
    }

    @Test
    fun does_not_snap_just_outside_explicit_maxSnap() {
        assertNull(AiRuleAnalyzerUtils.snapToAddedLine(7, setOf(1), maxSnap = 5))
    }

    @Test
    fun single_added_line_in_large_range() {
        assertEquals(50, AiRuleAnalyzerUtils.snapToAddedLine(48, setOf(50)))
    }
}
