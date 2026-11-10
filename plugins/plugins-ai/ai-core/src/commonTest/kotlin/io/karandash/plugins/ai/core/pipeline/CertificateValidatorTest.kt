package io.karandash.plugins.ai.core.pipeline

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CertificateValidatorTest {
    private fun fileChange(
        path: String,
        diffContent: String,
    ): FileChange =
        FileChange(
            path = path,
            changeType = ChangeType.MODIFIED,
            diffContent = diffContent,
        )

    @Test
    fun extractSectionsFromWellFormedCertificate() {
        val response = """
            Some preamble text.

            ### DEFINITIONS
            We check for null safety violations.

            ### PREMISES
            P1: [src/Foo.kt:10] — creates nullable variable

            ### EVIDENCE
            - Claim: Unsafe null assertion
            - Supporting code: [src/Foo.kt:10] `val x = input!!`
            - Counter-check: input parameter is nullable

            ### FINDINGS
            {"comments": [{"file": "src/Foo.kt", "line": 10, "message": "unsafe"}]}
        """.trimIndent()

        val sections = CertificateValidator.extractSections(response)

        assertTrue(sections.containsKey("DEFINITIONS"))
        assertTrue(sections.containsKey("PREMISES"))
        assertTrue(sections.containsKey("EVIDENCE"))
        assertTrue(sections.containsKey("FINDINGS"))
        assertTrue(
            sections["DEFINITIONS"]?.contains("null safety") == true,
        )
    }

    @Test
    fun extractSectionsReturnsEmptyForNoHeaders() {
        val response = "Just some plain text without any certificate sections."
        val sections = CertificateValidator.extractSections(response)
        assertTrue(sections.isEmpty())
    }

    @Test
    fun extractFindingsJsonFromFindingsSection() {
        val sections = mapOf(
            "FINDINGS" to """
                Based on the evidence above:
                {"comments": [{"file": "src/Foo.kt", "line": 10}]}
            """.trimIndent(),
        )

        val json = CertificateValidator.extractFindingsJson(sections)
        assertNotNull(json)
        assertTrue(json.contains("comments"))
    }

    @Test
    fun extractFindingsJsonReturnsNullWhenNoFindingsSection() {
        val sections = mapOf("EVIDENCE" to "some evidence")
        val json = CertificateValidator.extractFindingsJson(sections)
        assertNull(json)
    }

    @Test
    fun validateVerifiesCitationsAgainstChanges() {
        val response = """
            ### DEFINITIONS
            Check for unsafe operations.

            ### PREMISES
            P1: [src/Foo.kt:10] — creates variable

            ### EVIDENCE
            - Claim: Unsafe assertion
            - Supporting code: [src/Foo.kt:10] `val x = input!!`
            - Counter-check: parameter is nullable

            ### FINDINGS
            {"comments": [{"file": "src/Foo.kt", "line": 10, "message": "unsafe"}]}
        """.trimIndent()

        val changes = listOf(
            fileChange("src/Foo.kt", "+val x = input!!"),
        )

        val result = CertificateValidator.validate(response, changes)

        assertTrue(result.isComplete)
        assertNotNull(result.findingsJson)
        assertTrue(result.verifiedCitations.isNotEmpty())
    }

    @Test
    fun validateMarksUnverifiedCitationsForMissingFiles() {
        val response = """
            ### DEFINITIONS
            Check safety.

            ### EVIDENCE
            - Claim: Issue found
            - Supporting code: [src/Missing.kt:5] `bad code`
            - Counter-check: none

            ### FINDINGS
            {"comments": []}
        """.trimIndent()

        val changes = listOf(
            fileChange("src/Foo.kt", "+val x = 1"),
        )

        val result = CertificateValidator.validate(response, changes)

        assertTrue(result.unverifiedCitations.isNotEmpty())
        assertEquals(
            "file not in MR changes",
            result.unverifiedCitations.first().reason,
        )
    }

    @Test
    fun validateIsIncompleteWithoutEvidenceSection() {
        val response = """
            ### DEFINITIONS
            Something.

            ### FINDINGS
            {"comments": []}
        """.trimIndent()

        val result = CertificateValidator.validate(response, emptyList())

        assertTrue(!result.isComplete || result.sections.containsKey("EVIDENCE"))
    }

    @Test
    fun validateHandlesEmptyResponse() {
        val result = CertificateValidator.validate("", emptyList())

        assertTrue(!result.isComplete)
        assertNull(result.findingsJson)
        assertTrue(result.verifiedCitations.isEmpty())
    }

    @Test
    fun validateWithMultipleCitationsInEvidence() {
        val response = """
            ### DEFINITIONS
            Check correctness.

            ### EVIDENCE
            - Claim: First issue
            - Supporting code: [src/A.kt:10] `code a`
            - Counter-check: reason a

            - Claim: Second issue
            - Supporting code: [src/B.kt:20] `code b`
            - Counter-check: reason b

            ### FINDINGS
            {"comments": []}
        """.trimIndent()

        val changes = listOf(
            fileChange("src/A.kt", "+code a"),
            fileChange("src/B.kt", "+code b"),
        )

        val result = CertificateValidator.validate(response, changes)

        assertTrue(result.isComplete)
        assertTrue(result.verifiedCitations.size >= 2)
    }
}
