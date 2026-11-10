package io.karandash.plugins.ai.analyzers.reviewcomments

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import io.karandash.api.ai.AiTokenBudget
import io.karandash.api.context.PrecomputedDiffContext
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.core.rules.FixSuggestionLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewCommentsAnalyzerModuleTest {
    private val module = ReviewCommentsAnalyzerModule()

    private val stubBudget = object : AiTokenBudget {
        override fun canSpend(estimatedTokens: Int): Boolean = true

        override fun recordSpend(tokens: Int) {}

        override fun remaining(): Int = Int.MAX_VALUE

        override fun isExhausted(): Boolean = false
    }

    private val stubAiService = object : AiService {
        override val isAvailable: Boolean = false

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?
        ): AiCompletionResponse = error("not used in test")
    }

    private fun contextFor(mr: MergeRequest): AiAnalyzerContext =
        AiAnalyzerContext(
            mr = mr,
            aiService = stubAiService,
            budget = stubBudget,
            model = "test-model",
            diffContext = PrecomputedDiffContext(
                preparedDiff = mr.changes.joinToString("\n") { "### ${it.path}\n${it.diffContent ?: ""}" },
            ),
        )

    private val sampleMr = MergeRequest(
        id = "1",
        iid = 1,
        title = "feat: add validation",
        description = "Adds input validation",
        sourceBranch = "feature/validation",
        targetBranch = "main",
        author = User(username = "dev", name = "Dev"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        commits = emptyList(),
        changes = listOf(
            FileChange(
                path = "src/Main.kt",
                changeType = ChangeType.MODIFIED,
                additions = 10,
                deletions = 2,
                diffContent = "@@ -1,5 +1,13 @@\n+fun validate() {\n+    println(\"ok\")\n+}",
            ),
        ),
    )

    @Test
    fun parseResponse_extracts_suggestedCode_into_metadata() {
        val json = """
        {
          "comments": [
            {
              "file": "src/Main.kt",
              "line": 5,
              "endLine": 7,
              "category": "bug_risk",
              "severity": "warning",
              "message": "Missing null check",
              "existingCode": "val x = value",
              "suggestedCode": "val x = value ?: return"
            }
          ]
        }
        """.trimIndent()

        val findings = module.parseResponse(json, "test-model", 100, sampleMr)

        assertEquals(2, findings.size)

        val reviewFinding = findings[0]
        assertEquals(Severity.WARNING, reviewFinding.severity)
        assertEquals("Missing null check", reviewFinding.message)
        assertEquals("review-comments", reviewFinding.rule)
        assertNull(reviewFinding.metadata[AiFindingMetadataKeys.SUGGESTED_CODE])
        assertNull(reviewFinding.metadata[AiFindingMetadataKeys.EXISTING_CODE])

        val suggestionFinding = findings[1]
        assertEquals(Severity.WARNING, suggestionFinding.severity)
        assertEquals("Code suggestion (BUG)", suggestionFinding.message)
        assertEquals("code-suggestions", suggestionFinding.rule)
        assertEquals("val x = value", suggestionFinding.metadata[AiFindingMetadataKeys.EXISTING_CODE])
        assertEquals("val x = value ?: return", suggestionFinding.metadata[AiFindingMetadataKeys.SUGGESTED_CODE])

        // addedLines = {1, 2, 3}. Default maxSnap=2 so:
        //   line=5    → delta=2 from 3 → snaps to 3
        //   endLine=7 → delta=4 from 3 → exceeds maxSnap=2, becomes null
        val loc = suggestionFinding.location as Location.FileRef
        assertEquals("src/Main.kt", loc.path)
        assertEquals(3, loc.line)
        assertNull(loc.endLine)
    }

    @Test
    fun `fix field becomes the finding suggestion and stays out of the message`() {
        val json = """{"comments":[{"file":"src/Main.kt","line":3,"category":"bug","severity":"warning",""" +
            """"message":"name can be null here","fix":"Return early when name is null"}]}"""

        val finding = module.parseResponse(json, "test-model", 50, sampleMr).single()

        assertEquals("name can be null here", finding.message)
        assertEquals("Return early when name is null", finding.suggestion)
    }

    @Test
    fun `prompt and response format ask for the optional fix field`() {
        assertTrue(module.responseFormatSuffix.contains("\"fix\""))
        assertTrue(module.defaultPrompt.contains("- fix: (optional)"))
    }

    @Test
    fun parseResponse_works_without_suggestedCode() {
        val json = """
        {
          "comments": [
            {
              "file": "src/Main.kt",
              "line": 3,
              "category": "style",
              "severity": "info",
              "message": "Consider renaming variable"
            }
          ]
        }
        """.trimIndent()

        val findings = module.parseResponse(json, "test-model", 50, sampleMr)

        assertEquals(1, findings.size)
        assertNull(findings[0].metadata[AiFindingMetadataKeys.SUGGESTED_CODE])

        val loc = findings[0].location as Location.FileRef
        assertNull(loc.endLine)
    }

    @Test
    fun parseResponse_handles_endLine_without_suggestedCode() {
        val json = """
        {
          "comments": [
            {
              "file": "src/Main.kt",
              "line": 2,
              "endLine": 4,
              "category": "performance",
              "severity": "warning",
              "message": "Inefficient loop"
            }
          ]
        }
        """.trimIndent()

        val findings = module.parseResponse(json, "test-model", 50, sampleMr)

        assertEquals(1, findings.size)
        val loc = findings[0].location as Location.FileRef
        assertEquals(2, loc.line)
        // endLine=4 is one past addedLines {1,2,3}; snaps back to 3
        assertEquals(3, loc.endLine)
        assertNull(findings[0].metadata[AiFindingMetadataKeys.SUGGESTED_CODE])
    }

    @Test
    fun parseResponse_filters_invalid_file_paths() {
        val json = """
        {
          "comments": [
            {
              "file": "nonexistent/File.kt",
              "line": 1,
              "category": "bug_risk",
              "severity": "error",
              "message": "Bad file"
            }
          ]
        }
        """.trimIndent()

        val findings = module.parseResponse(json, "test-model", 50, sampleMr)

        assertTrue(findings.isEmpty())
    }

    @Test
    fun parseResponse_filters_zero_line_numbers() {
        val json = """
        {
          "comments": [
            {
              "file": "src/Main.kt",
              "line": 0,
              "category": "bug_risk",
              "severity": "error",
              "message": "Zero line"
            }
          ]
        }
        """.trimIndent()

        val findings = module.parseResponse(json, "test-model", 50, sampleMr)

        assertTrue(findings.isEmpty())
    }

    @Test
    fun parseResponse_blank_suggestedCode_not_in_metadata() {
        val json = """
        {
          "comments": [
            {
              "file": "src/Main.kt",
              "line": 3,
              "category": "style",
              "severity": "info",
              "message": "Needs fix",
              "suggestedCode": "   "
            }
          ]
        }
        """.trimIndent()

        val findings = module.parseResponse(json, "test-model", 50, sampleMr)

        assertEquals(1, findings.size)
        assertNull(findings[0].metadata[AiFindingMetadataKeys.SUGGESTED_CODE])
    }

    @Test
    fun buildPrompt_puts_MR_metadata_in_the_context_block_and_files_in_the_prompt() {
        val contextBlock = module.buildContextBlock(contextFor(sampleMr))
        val prompt = module.buildPrompt(contextFor(sampleMr))

        assertTrue(contextBlock.contains("feat: add validation"))
        assertTrue(contextBlock.contains("feature/validation"))
        assertTrue(contextBlock.contains("main"))
        assertFalse(contextBlock.contains("src/Main.kt"), "the changed files vary with the diff")
        assertTrue(prompt.contains("src/Main.kt"))
    }

    @Test
    fun module_metadata_is_correct() {
        assertEquals("ai-review-comments-analyzer", module.id)
        assertEquals("AI Review Comments", module.name)
        assertEquals("review-comments", module.analyzerId)
        assertTrue(module.defaultEnabled)
    }

    @Test
    fun fixSuggestionMinSeverity_filters_info_by_default() {
        val json = """
        {
          "comments": [
            {
              "file": "src/Main.kt",
              "line": 3,
              "category": "style",
              "severity": "info",
              "message": "Style nit",
              "existingCode": "val x = 0",
              "suggestedCode": "val x = 1"
            }
          ]
        }
        """.trimIndent()

        val findings = module.parseResponse(json, "test-model", 50, sampleMr)

        assertEquals(1, findings.size)
        assertNull(
            findings[0].metadata[AiFindingMetadataKeys.SUGGESTED_CODE],
            "INFO findings should not include suggestedCode with default min severity (warning)",
        )
    }

    @Test
    fun fixSuggestionMinSeverity_includes_warning_by_default() {
        val json = """
        {
          "comments": [
            {
              "file": "src/Main.kt",
              "line": 3,
              "category": "bug_risk",
              "severity": "warning",
              "message": "Potential issue",
              "existingCode": "val x = value",
              "suggestedCode": "val x = value ?: return"
            }
          ]
        }
        """.trimIndent()

        val findings = module.parseResponse(json, "test-model", 50, sampleMr)

        assertEquals(2, findings.size)
        assertEquals("review-comments", findings[0].rule)
        assertNull(findings[0].metadata[AiFindingMetadataKeys.SUGGESTED_CODE])
        assertEquals("code-suggestions", findings[1].rule)
        assertNotNull(
            findings[1].metadata[AiFindingMetadataKeys.SUGGESTED_CODE],
            "WARNING findings should include suggestedCode with default min severity",
        )
    }

    @Test
    fun `parse keeps every comment in model order for the whole-MR cap`() {
        val json = """
        {
          "comments": [
            {"file": "src/Main.kt", "line": 1, "category": "performance", "severity": "warning", "message": "weak", "confidence": 0.6},
            {"file": "src/Main.kt", "line": 2, "category": "performance", "severity": "warning", "message": "sure", "confidence": 0.9},
            {"file": "src/Main.kt", "line": 3, "category": "security", "severity": "error", "message": "worst", "confidence": 0.7}
          ]
        }
        """.trimIndent()

        val findings = module.parseResponse(json, "test-model", 50)

        assertEquals(listOf("weak", "sure", "worst"), findings.map { it.message })
    }

    @Test
    fun fixSuggestionMinSeverity_error_only_excludes_warning() {
        val json = """
        {
          "comments": [
            {
              "file": "src/Main.kt",
              "line": 3,
              "category": "bug_risk",
              "severity": "warning",
              "message": "Potential issue",
              "existingCode": "val x = value",
              "suggestedCode": "val x = value ?: return"
            },
            {
              "file": "src/Main.kt",
              "line": 5,
              "category": "security",
              "severity": "error",
              "message": "SQL injection",
              "existingCode": "db.query(raw)",
              "suggestedCode": "db.query(prepared)"
            }
          ]
        }
        """.trimIndent()

        val config = AiRuleConfig(fixSuggestion = FixSuggestionLimits(minSeverity = "error"))
        val findings = module.parseResponse(json, "test-model", 50, sampleMr, config)

        assertEquals(3, findings.size)
        // WARNING comment: 1 finding (below threshold, no split)
        assertEquals("review-comments", findings[0].rule)
        assertNull(
            findings[0].metadata[AiFindingMetadataKeys.SUGGESTED_CODE],
            "WARNING finding should not have suggestedCode when min severity is error",
        )
        // ERROR comment: 2 findings (split)
        assertEquals("review-comments", findings[1].rule)
        assertNull(findings[1].metadata[AiFindingMetadataKeys.SUGGESTED_CODE])
        assertEquals("code-suggestions", findings[2].rule)
        assertNotNull(
            findings[2].metadata[AiFindingMetadataKeys.SUGGESTED_CODE],
            "ERROR finding should have suggestedCode when min severity is error",
        )
    }

    @Test
    fun fixSuggestionMinSeverity_info_includes_all() {
        val json = """
        {
          "comments": [
            {
              "file": "src/Main.kt",
              "line": 3,
              "category": "style",
              "severity": "info",
              "message": "Style nit",
              "existingCode": "val x = 0",
              "suggestedCode": "val x = 1"
            }
          ]
        }
        """.trimIndent()

        val config = AiRuleConfig(fixSuggestion = FixSuggestionLimits(minSeverity = "info"))
        val findings = module.parseResponse(json, "test-model", 50, sampleMr, config)

        assertEquals(2, findings.size)
        assertEquals("review-comments", findings[0].rule)
        assertEquals("code-suggestions", findings[1].rule)
        assertNotNull(
            findings[1].metadata[AiFindingMetadataKeys.SUGGESTED_CODE],
            "INFO finding should have suggestedCode when min severity is info",
        )
    }

    @Test
    fun parseResponse_drops_suggestedCode_without_existingCode() {
        val json = """
        {
          "comments": [
            {
              "file": "src/Main.kt",
              "line": 5,
              "category": "bug_risk",
              "severity": "warning",
              "message": "Missing null check",
              "suggestedCode": "val x = value ?: return"
            }
          ]
        }
        """.trimIndent()

        val findings = module.parseResponse(json, "test-model", 100, sampleMr)

        assertEquals(1, findings.size)
        assertEquals("review-comments", findings[0].rule)
        assertNull(
            findings[0].metadata[AiFindingMetadataKeys.SUGGESTED_CODE],
            "suggestedCode without existingCode should be dropped",
        )
        assertNull(findings[0].metadata[AiFindingMetadataKeys.EXISTING_CODE])
    }

    @Test
    fun parseResponse_split_findings_share_location() {
        val json = """
        {
          "comments": [
            {
              "file": "src/Main.kt",
              "line": 5,
              "endLine": 7,
              "category": "bug_risk",
              "severity": "warning",
              "message": "Missing null check",
              "existingCode": "val x = value",
              "suggestedCode": "val x = value ?: return"
            }
          ]
        }
        """.trimIndent()

        val findings = module.parseResponse(json, "test-model", 100, sampleMr)

        assertEquals(2, findings.size)
        val reviewLoc = findings[0].location as Location.FileRef
        val suggestionLoc = findings[1].location as Location.FileRef
        assertEquals(reviewLoc.path, suggestionLoc.path)
        assertEquals(reviewLoc.line, suggestionLoc.line)
        assertEquals(reviewLoc.endLine, suggestionLoc.endLine)
    }

    @Test
    fun parseResponse_split_review_finding_has_no_code_metadata() {
        val json = """
        {
          "comments": [
            {
              "file": "src/Main.kt",
              "line": 5,
              "category": "bug_risk",
              "severity": "error",
              "message": "SQL injection risk",
              "existingCode": "db.query(raw)",
              "suggestedCode": "db.query(prepared)"
            }
          ]
        }
        """.trimIndent()

        val findings = module.parseResponse(json, "test-model", 100, sampleMr)

        assertEquals(2, findings.size)
        val reviewFinding = findings[0]
        assertEquals("review-comments", reviewFinding.rule)
        assertNull(reviewFinding.metadata[AiFindingMetadataKeys.EXISTING_CODE])
        assertNull(reviewFinding.metadata[AiFindingMetadataKeys.SUGGESTED_CODE])
    }

    @Test
    fun parseResponse_recovers_valid_comments_from_partially_malformed_json() {
        // 3 comments: 1st valid, 2nd has unescaped quotes breaking JSON parse, 3rd valid
        // The 2nd comment's "message" contains an unescaped " which corrupts the JSON structure
        val json = "{\"comments\": [" +
            "{\"file\": \"src/Main.kt\", \"line\": 1, \"category\": \"bug_risk\", " +
            "\"severity\": \"warning\", \"message\": \"First comment is valid\"}, " +
            "{\"file\": \"src/Main.kt\", \"line\": 2, \"category\": \"style\", " +
            "\"severity\": \"info\", \"message\": \"Use pattern \"^(feat|fix):\" instead\"}, " +
            "{\"file\": \"src/Main.kt\", \"line\": 3, \"category\": \"performance\", " +
            "\"severity\": \"warning\", \"message\": \"Third comment is valid\"}" +
            "]}"

        val findings = module.parseResponse(json, "test-model", 100, sampleMr)

        // Should recover 2 valid comments, skipping the malformed 2nd one
        assertEquals(2, findings.size)
        assertEquals("First comment is valid", findings[0].message)
        assertEquals("Third comment is valid", findings[1].message)
    }

    @Test
    fun parseResponse_falls_back_to_error_when_all_comments_malformed() {
        val json = """
        {
          "comments": [
            {bad json here},
            {also bad}
          ]
        }
        """.trimIndent()

        val findings = module.parseResponse(json, "test-model", 100, sampleMr)

        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("unparseable response"))
    }

    @Test
    fun parseResponse_recovery_applies_file_path_validation() {
        // Force recovery path with an unescaped quote, then verify file path filtering
        val json = "{\"comments\": [" +
            "{\"file\": \"src/Main.kt\", \"line\": 1, \"category\": \"bug_risk\", " +
            "\"severity\": \"warning\", \"message\": \"Valid file\"}, " +
            "{\"file\": \"src/Main.kt\", \"line\": 2, \"category\": \"style\", " +
            "\"severity\": \"info\", \"message\": \"broken \"quote\" here\"}, " +
            "{\"file\": \"nonexistent/File.kt\", \"line\": 1, \"category\": \"style\", " +
            "\"severity\": \"info\", \"message\": \"Bad file path\"}" +
            "]}"

        val findings = module.parseResponse(json, "test-model", 100, sampleMr)

        // Recovery parses 1st and 3rd, but file validation filters the nonexistent path
        assertEquals(1, findings.size)
        assertEquals("Valid file", findings[0].message)
    }

    @Test
    fun parseResponse_stores_confidence_in_metadata() {
        val json = """
        {
          "comments": [
            {
              "file": "src/Main.kt",
              "line": 3,
              "category": "bug_risk",
              "severity": "warning",
              "confidence": 0.92,
              "message": "Potential NPE"
            }
          ]
        }
        """.trimIndent()

        val findings = module.parseResponse(json, "test-model", 50, sampleMr)

        assertEquals(1, findings.size)
        assertEquals("0.92", findings[0].metadata[AiFindingMetadataKeys.CONFIDENCE])
    }

    @Test
    fun parseResponse_uses_default_confidence_when_missing() {
        val json = """
        {
          "comments": [
            {
              "file": "src/Main.kt",
              "line": 3,
              "category": "style",
              "severity": "info",
              "message": "Consider renaming"
            }
          ]
        }
        """.trimIndent()

        val findings = module.parseResponse(json, "test-model", 50, sampleMr)

        assertEquals(1, findings.size)
        assertEquals(
            ReviewComment.DEFAULT_CONFIDENCE.toString(),
            findings[0].metadata[AiFindingMetadataKeys.CONFIDENCE],
        )
    }

    @Test
    fun parseResponse_confidence_stored_in_suggestion_metadata() {
        val json = """
        {
          "comments": [
            {
              "file": "src/Main.kt",
              "line": 3,
              "category": "bug_risk",
              "severity": "warning",
              "confidence": 0.88,
              "message": "Missing null check",
              "existingCode": "val x = value",
              "suggestedCode": "val x = value ?: return"
            }
          ]
        }
        """.trimIndent()

        val findings = module.parseResponse(json, "test-model", 100, sampleMr)

        assertEquals(2, findings.size)
        assertEquals("0.88", findings[0].metadata[AiFindingMetadataKeys.CONFIDENCE])
        assertEquals("0.88", findings[1].metadata[AiFindingMetadataKeys.CONFIDENCE])
    }

    @Test
    fun `every comment splits into a review and a code-suggestion finding without a cut`() {
        val json = """
        {
          "comments": [
            {
              "file": "src/Main.kt",
              "line": 1,
              "category": "BUG",
              "severity": "warning",
              "message": "Issue A",
              "existingCode": "val a = 1",
              "suggestedCode": "val a = 2"
            },
            {
              "file": "src/Main.kt",
              "line": 3,
              "category": "BUG",
              "severity": "warning",
              "message": "Issue B",
              "existingCode": "val b = 1",
              "suggestedCode": "val b = 2"
            },
            {
              "file": "src/Main.kt",
              "line": 5,
              "category": "BUG",
              "severity": "warning",
              "message": "Issue C",
              "existingCode": "val c = 1",
              "suggestedCode": "val c = 2"
            }
          ]
        }
        """.trimIndent()

        val findings = module.parseResponse(json, "test-model", 100, sampleMr)

        assertEquals(
            List(3) { listOf("review-comments", "code-suggestions") }.flatten(),
            findings.map { it.rule },
        )
    }
}
