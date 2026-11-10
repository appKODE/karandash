package io.karandash.plugins.ai.analyzers.codesuggestions

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.core.rules.CodeSuggestionLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CodeSuggestionsAnalyzerModuleTest {
    private val module = CodeSuggestionsAnalyzerModule()

    @Test
    fun module_metadata_is_correct() {
        assertEquals("ai-code-suggestions-analyzer", module.id)
        assertEquals("AI Code Suggestions", module.name)
        assertEquals("code-suggestions", module.analyzerId)
        assertEquals("codeSuggestions", module.featureKey)
        assertEquals(false, module.defaultEnabled)
    }

    @Test
    fun parseResponse_extracts_suggestion_into_finding() {
        val json = """
        {
          "suggestions": [
            {
              "file": "src/Foo.kt",
              "line": 42,
              "existingCode": "val x = list.filter { it != null }.first()",
              "suggestedCode": "val x = list.firstOrNull { it != null }",
              "explanation": "Use firstOrNull for null safety",
              "category": "correctness"
            }
          ]
        }
        """.trimIndent()

        val findings = module.parseResponse(json, "test-model", 100, 10)

        assertEquals(1, findings.size)
        val finding = findings[0]
        assertEquals(Severity.WARNING, finding.severity)
        assertEquals("Use firstOrNull for null safety", finding.message)
        assertNotNull(finding.metadata[AiFindingMetadataKeys.SUGGESTED_CODE])
        assertNotNull(finding.metadata[AiFindingMetadataKeys.EXISTING_CODE])
    }

    @Test
    fun parseResponse_maps_severity_by_category() {
        val json = """
        {
          "suggestions": [
            {
              "file": "src/A.kt",
              "line": 1,
              "existingCode": "a",
              "suggestedCode": "b",
              "explanation": "Security fix",
              "category": "security"
            },
            {
              "file": "src/B.kt",
              "line": 2,
              "existingCode": "c",
              "suggestedCode": "d",
              "explanation": "Readability",
              "category": "readability"
            }
          ]
        }
        """.trimIndent()

        val findings = module.parseResponse(json, "test-model", 100, 10)

        assertEquals(2, findings.size)
        assertEquals(Severity.WARNING, findings[0].severity)
        assertEquals(Severity.INFO, findings[1].severity)
    }

    @Test
    fun parseResponse_filters_zero_line_numbers() {
        val json = """
        {
          "suggestions": [
            {
              "file": "src/Foo.kt",
              "line": 0,
              "existingCode": "a",
              "suggestedCode": "b",
              "explanation": "Fix",
              "category": "correctness"
            }
          ]
        }
        """.trimIndent()

        val findings = module.parseResponse(json, "test-model", 50, 10)

        assertTrue(findings.isEmpty())
    }

    @Test
    fun parseResponse_respects_maxSuggestions_limit() {
        val json = """
        {
          "suggestions": [
            {"file": "src/A.kt", "line": 1, "existingCode": "a", "suggestedCode": "b", "explanation": "A", "category": "readability"},
            {"file": "src/B.kt", "line": 2, "existingCode": "c", "suggestedCode": "d", "explanation": "B", "category": "readability"},
            {"file": "src/C.kt", "line": 3, "existingCode": "e", "suggestedCode": "f", "explanation": "C", "category": "readability"}
          ]
        }
        """.trimIndent()

        val findings = module.parseResponse(json, "test-model", 50, 2)

        assertEquals(2, findings.size)
    }

    @Test
    fun codeSuggestionMode_inline_is_default() {
        val config = AiRuleConfig()
        assertEquals("inline", config.codeSuggestionMode)
    }

    @Test
    fun codeSuggestionMode_separate_is_configurable() {
        val config = AiRuleConfig(codeSuggestion = CodeSuggestionLimits(mode = "separate"))
        assertEquals("separate", config.codeSuggestionMode)
    }

    // --- diff-snap tests ---

    private fun mrWith(
        path: String,
        diffContent: String?,
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

    private fun suggestionJson(
        file: String,
        line: Int
    ) = """
        {
          "suggestions": [
            {
              "file": "$file",
              "line": $line,
              "existingCode": "old",
              "suggestedCode": "new",
              "explanation": "test fix",
              "category": "correctness"
            }
          ]
        }
        """.trimIndent()

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

    private val deletedFileDiff = """
        --- a/src/service/OldService.kt
        +++ /dev/null
        @@ -1,4 +0,0 @@
        -package com.example
        -
        -class OldService {
        -}
    """.trimIndent()

    @Test
    fun parseResponse_line_on_added_line_passes_through() {
        // multiHunkDiff addedLines = {3, 16, 18}; line 3 is added
        val mr = mrWith("src/Calculator.kt", multiHunkDiff)
        val findings = module.parseResponse(suggestionJson("src/Calculator.kt", 3), "m", 0, 10, mr)
        assertEquals(1, findings.size)
        assertEquals(3, (findings[0].location as Location.FileRef).line)
    }

    @Test
    fun parseResponse_context_line_more_than_2_away_is_filtered_out() {
        // realWorldDiff addedLines = {581}; LLM reports 584 (delta=3).
        // Default maxSnap=2 - drop the suggestion rather than silently relocate
        // onto an unrelated context line.
        val mr = mrWith("src/physics/Simulation.java", realWorldDiff)
        val findings = module.parseResponse(suggestionJson("src/physics/Simulation.java", 584), "m", 0, 10, mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun parseResponse_line_beyond_snap_range_is_filtered_out() {
        // realWorldDiff addedLines = {581}; line 200 is far beyond any plausible snap
        val mr = mrWith("src/physics/Simulation.java", realWorldDiff)
        val findings = module.parseResponse(suggestionJson("src/physics/Simulation.java", 200), "m", 0, 10, mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun parseResponse_null_mr_passes_lines_through_unchanged() {
        val findings = module.parseResponse(suggestionJson("src/Foo.kt", 99), "m", 0, 10, mr = null)
        assertEquals(1, findings.size)
        assertEquals(99, (findings[0].location as Location.FileRef).line)
    }

    @Test
    fun parseResponse_deleted_file_empty_diff_passes_lines_through() {
        // deletedFileDiff has no added lines → lines pass through unchanged
        val mr = mrWith("src/service/OldService.kt", deletedFileDiff)
        val findings = module.parseResponse(suggestionJson("src/service/OldService.kt", 2), "m", 0, 10, mr)
        assertEquals(1, findings.size)
        assertEquals(2, (findings[0].location as Location.FileRef).line)
    }

    @Test
    fun parseResponse_null_diff_passes_lines_through() {
        val mr = mrWith("src/Main.kt", null)
        val findings = module.parseResponse(suggestionJson("src/Main.kt", 42), "m", 0, 10, mr)
        assertEquals(1, findings.size)
        assertEquals(42, (findings[0].location as Location.FileRef).line)
    }
}
