package io.karandash.plugins.ai.analyzers.mrsummary

import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.pipeline.AiMetadataTransformer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MrSummaryAnalyzerModuleTest {
    private val module = MrSummaryAnalyzerModule()

    private val fullResponseJson = """
        {
          "summary": "This MR adds OAuth2 support.",
          "keyChanges": ["Added OAuthClient", "Updated AuthHandler"],
          "risks": ["Token expiry not handled"],
          "reviewFocus": ["Check token refresh logic"]
        }
    """.trimIndent()

    @Test
    fun parseResponse_finding_message_contains_full_structured_text() {
        val findings = module.parseResponse(fullResponseJson, "test-model", 42)

        assertEquals(1, findings.size)
        val message = findings[0].message
        assertTrue(message.contains("This MR adds OAuth2 support."))
        assertTrue(message.contains("**Key changes:**"))
        assertTrue(message.contains("Added OAuthClient"))
        assertTrue(message.contains("**Risks:**"))
        assertTrue(message.contains("Token expiry not handled"))
        assertTrue(message.contains("**Review focus:**"))
        assertTrue(message.contains("Check token refresh logic"))
    }

    @Test
    fun parseResponse_finding_has_no_explanation_field() {
        val findings = module.parseResponse(fullResponseJson, "test-model", 42)

        assertNull(findings[0].explanation)
    }

    @Test
    fun parseResponse_finding_metadata_has_no_aiSummary_key() {
        val findings = module.parseResponse(fullResponseJson, "test-model", 42)

        assertFalse(findings[0].metadata.containsKey(AiFindingMetadataKeys.AI_SUMMARY))
    }

    @Test
    fun parseResponse_finding_passes_through_transformer_without_message_baking() {
        val findings = module.parseResponse(fullResponseJson, "test-model", 42)
        val originalMessage = findings[0].message

        val transformed = AiMetadataTransformer.transform(findings)

        // Message must not be modified - no aiSummary in metadata means nothing to bake in.
        assertEquals(originalMessage, transformed[0].message)
    }

    @Test
    fun parseResponse_metadata_contains_isMrSummary_flag() {
        val findings = module.parseResponse(fullResponseJson, "test-model", 42)

        assertEquals("true", findings[0].metadata[AiFindingMetadataKeys.IS_MR_SUMMARY])
    }

    @Test
    fun parseResponse_leaves_aiModel_and_aiTokensUsed_to_the_dispatcher() {
        val findings = module.parseResponse(fullResponseJson, "gpt-4o", 123)

        val meta = findings[0].metadata
        assertFalse(AiFindingMetadataKeys.AI_MODEL in meta)
        assertFalse(AiFindingMetadataKeys.AI_TOKENS_USED in meta)
    }

    @Test
    fun parseResponse_summary_only_response_omits_empty_sections() {
        val json = """{"summary": "Simple summary."}"""

        val findings = module.parseResponse(json, "test-model", 10)

        assertEquals(1, findings.size)
        assertEquals("Simple summary.", findings[0].message)
        assertFalse(findings[0].message.contains("**Key changes:**"))
        assertFalse(findings[0].message.contains("**Risks:**"))
        assertFalse(findings[0].message.contains("**Review focus:**"))
    }

    @Test
    fun parseResponse_reviewFocus_scalar_string_is_coerced_to_single_item_list() {
        val json = """
            {
              "summary": "Updates payment-methods API to v4.",
              "keyChanges": ["Added /v4/payment-types endpoint"],
              "risks": ["Potential display issues if API v4 response differs"],
              "reviewFocus": "Validate conditional logic in both ViewModels handling 'Other' payment type"
            }
        """.trimIndent()

        val findings = module.parseResponse(json, "qwen3.5-35b-a3b", 2339)

        assertEquals(1, findings.size)
        assertEquals(Severity.INFO, findings[0].severity)
        val message = findings[0].message
        assertTrue(message.contains("**Review focus:**"))
        assertTrue(
            message.contains("Validate conditional logic in both ViewModels handling 'Other' payment type"),
            "reviewFocus scalar should be rendered as a single bullet, got: $message",
        )
    }

    @Test
    fun parseResponse_keyChanges_scalar_string_is_coerced() {
        val json = """
            {"summary": "Summary.", "keyChanges": "Only one change"}
        """.trimIndent()

        val findings = module.parseResponse(json, "test-model", 10)

        assertEquals(1, findings.size)
        assertEquals(Severity.INFO, findings[0].severity)
        val message = findings[0].message
        assertTrue(message.contains("**Key changes:**"))
        assertTrue(message.contains("- Only one change"))
    }

    @Test
    fun parseResponse_reviewFocus_null_becomes_empty_list() {
        val json = """{"summary": "s", "reviewFocus": null}"""

        val findings = module.parseResponse(json, "test-model", 10)

        assertEquals(1, findings.size)
        assertFalse(findings[0].message.contains("**Review focus:**"))
    }

    @Test
    fun parseResponse_truly_invalid_json_returns_warning_with_parseException_metadata() {
        val content = """not json, and no structure at all - just prose"""

        val findings = module.parseResponse(content, "test-model", 42)

        assertEquals(1, findings.size)
        assertEquals(Severity.INFO, findings[0].severity)
        assertTrue(findings[0].message.startsWith("AI MR summary generation returned unparseable response"))
    }

    @Test
    fun parseResponse_schema_mismatch_records_parseException_metadata() {
        // `summary` is a required String but sent as a JsonObject - forces SerializationException
        // after extractJson succeeds, exercising the new parseException metadata path.
        val content = """{"summary": {"nested": "wrong type"}, "reviewFocus": ["ok"]}"""

        val findings = module.parseResponse(content, "test-model", 42)

        assertEquals(1, findings.size)
        assertEquals(Severity.INFO, findings[0].severity)
        val parseException = findings[0].metadata["parseException"]
        assertNotNull(parseException, "parseException metadata should be recorded for schema errors")
        assertTrue(parseException.isNotEmpty())
    }
}
