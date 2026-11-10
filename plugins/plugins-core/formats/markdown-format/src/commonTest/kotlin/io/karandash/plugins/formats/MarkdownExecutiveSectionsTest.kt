package io.karandash.plugins.formats

import io.karandash.models.finding.Attachment
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.testfixtures.createTestFinding
import io.karandash.testfixtures.createTestReport
import kotlin.test.Test
import kotlin.test.assertTrue

class MarkdownExecutiveSectionsTest {
    private fun summary(vararg findings: Finding): String =
        formatSummaryMarkdown(createTestReport(findings = findings.toList()), findings.toList(), inlineCount = 0)

    private fun flagged(
        flag: String,
        message: String = "m",
        metadata: Map<String, String> = emptyMap(),
    ) = createTestFinding(message = message, metadata = metadata + (flag to "true"))

    private fun assertBlock(
        expected: String,
        body: String,
    ) = assertTrue(expected.trimIndent() in body, body)

    @Test
    fun `risk advisory renders score message and factors as one blockquote`() {
        val risk =
            flagged(
                WellKnownMetadataKeys.IS_RISK_ADVISORY,
                "Touches auth\nand billing",
                mapOf(WellKnownMetadataKeys.RISK_SCORE to "0.8", WellKnownMetadataKeys.RISK_FACTORS to "auth\nbilling"),
            )

        assertBlock(
            """
            > **⚠️ Risk Assessment** (score: 0.8)
            >
            > Touches auth and billing
            >
            > **Risk factors:** auth billing
            """,
            summary(risk),
        )
    }

    @Test
    fun `description quality lists semicolon separated issues`() {
        val quality =
            flagged(WellKnownMetadataKeys.IS_DESCRIPTION_QUALITY, "Thin", mapOf(WellKnownMetadataKeys.QUALITY_SCORE to "3"))
                .copy(suggestion = "no why; ; no test plan")

        assertBlock(
            """
            > **⚠️ Description Quality** (score: 3)
            >
            > Thin
            >
            > - no why
            > - no test plan
            """,
            summary(quality),
        )
    }

    @Test
    fun `suggested labels come from metadata or fall back to the message`() {
        val labels =
            flagged(WellKnownMetadataKeys.IS_CATEGORIZATION, metadata = mapOf(WellKnownMetadataKeys.SUGGESTED_LABELS to "bug, ui"))
                .copy(explanation = "fixes\na crash")
        val noLabels = flagged(WellKnownMetadataKeys.IS_CATEGORIZATION, "feature")

        val body = summary(labels, noLabels)

        assertBlock("> 🏷️ **Suggested Labels:** `bug`, `ui`\n>\n> *fixes a crash*", body)
        assertBlock("> 🏷️ **Suggested Labels:** feature\n\n", body)
    }

    @Test
    fun `ticket compliance shows percent and requirement lists`() {
        val ticket =
            flagged(
                WellKnownMetadataKeys.IS_TICKET_COMPLIANCE,
                "Half done",
                mapOf(
                    WellKnownMetadataKeys.COMPLIANCE_SCORE to "0.5",
                    WellKnownMetadataKeys.FULFILLED_REQUIREMENTS to "login; logout",
                    WellKnownMetadataKeys.PARTIAL_REQUIREMENTS to "audit",
                    WellKnownMetadataKeys.MISSING_REQUIREMENTS to "sso",
                ),
            )
        val nullScore = flagged(WellKnownMetadataKeys.IS_TICKET_COMPLIANCE, "n/a", mapOf(WellKnownMetadataKeys.COMPLIANCE_SCORE to "null"))
        val badScore = flagged(WellKnownMetadataKeys.IS_TICKET_COMPLIANCE, "odd", mapOf(WellKnownMetadataKeys.COMPLIANCE_SCORE to "high"))

        val body = summary(ticket, nullScore, badScore)

        assertBlock(
            """
            > **⚠️ Ticket Compliance** (50%)
            >
            > Half done
            >
            > - ✅ login
            > - ✅ logout
            > - ◐ audit
            > - ❌ sso
            """,
            body,
        )
        assertBlock("> **⚠️ Ticket Compliance**\n>\n> n/a\n\n", body)
        assertBlock("> **⚠️ Ticket Compliance**\n>\n> odd\n\n", body)
    }

    @Test
    fun `fix suggestions pr split and attachments render in their sections`() {
        val fix =
            flagged(
                WellKnownMetadataKeys.IS_FIX_SUGGESTION,
                metadata = mapOf(WellKnownMetadataKeys.ORIGINAL_RULE to "size", WellKnownMetadataKeys.SUGGESTED_FIX to "split it"),
            )
        val bareFix = flagged(WellKnownMetadataKeys.IS_FIX_SUGGESTION, "rename")
        val split = flagged(WellKnownMetadataKeys.IS_PR_SPLIT, "split plan")
        val regular = createTestFinding(
            severity = Severity.ERROR,
            message = "bad"
        ).copy(attachments = listOf(Attachment("log.txt", "trace")))

        val body = summary(fix, bareFix, split, regular)

        assertBlock("### AI Fix Suggestions\n\n> 💡 **Fix for `size`:** split it\n\n> 💡 **Fix for `test-rule`:** rename", body)
        assertBlock("<details><summary>🔀 PR Split Analysis</summary>\n\nsplit plan\n\n</details>", body)
        assertBlock("| ❌ ERROR | `test-rule` | bad |  |\n\n<details><summary>log.txt</summary>\n\ntrace\n</details>", body)
    }
}
