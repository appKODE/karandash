package io.karandash.plugins.formats

import io.karandash.api.finding.SkipCategory
import io.karandash.api.finding.SkippedRuleInfo
import io.karandash.models.ActivePolicy
import io.karandash.models.finding.Attachment
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Mismatch
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.testfixtures.createTestFinding
import io.karandash.testfixtures.createTestReport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonReportFormatModuleOutputTest {
    private val module = JsonReportFormatModule()

    @Test
    fun `empty report renders passed with zero summary and no optional blocks`() =
        runTest {
            val report = module.format(createTestReport(findings = emptyList()), JsonObject(emptyMap()))

            assertEquals("json", report.formatId)
            assertEquals(
                """
                {
                    "schemaVersion": 1,
                    "passed": true,
                    "findings": [],
                    "summary": {
                        "errors": 0,
                        "warnings": 0,
                        "info": 0
                    }
                }
                """.trimIndent(),
                report.body,
            )
        }

    @Test
    fun `full finding policies and skipped rules render every field`() =
        runTest {
            val finding =
                Finding(
                    severity = Severity.ERROR,
                    message = "Quote \" and\nnewline <tag> ü",
                    rule = "titleFormat",
                    location = Location.FileRef("src/A.kt", 7),
                    suggestion = "fix it",
                    explanation = "because",
                    metadata = mapOf("snake_key" to "v"),
                    attachments = listOf(Attachment("a.md", "# hi")),
                )
            val base = createTestReport(findings = listOf(finding))
            val report =
                base.copy(
                    appliedPolicies =
                        listOf(
                            ActivePolicy(
                                condition = "branch == main",
                                matched = true,
                                affectedModules = listOf("m"),
                                forcedEnabled = null,
                                forcedSeverity = null,
                                configOverrides = null,
                            ),
                        ),
                    skippedRules =
                        listOf(
                            SkippedRuleInfo("draftPrevention", "", SkipCategory.WHEN_CONDITION),
                            SkippedRuleInfo("labels", "bail", SkipCategory.BAIL_ON_FAIL),
                        ),
                )

            val body = module.format(report, JsonObject(emptyMap())).body

            assertEquals(
                """
                {
                    "schemaVersion": 1,
                    "passed": false,
                    "findings": [
                        {
                            "severity": "error",
                            "message": "Quote \" and\nnewline <tag> ü",
                            "rule": "titleFormat",
                            "location": {
                                "file": "src/A.kt",
                                "line": 7
                            },
                            "suggestion": "fix it",
                            "explanation": "because",
                            "metadata": {
                                "snake_key": "v"
                            },
                            "attachments": [
                                {
                                    "filename": "a.md",
                                    "content": "# hi",
                                    "contentType": "text/markdown"
                                }
                            ]
                        }
                    ],
                    "summary": {
                        "errors": 1,
                        "warnings": 0,
                        "info": 0
                    },
                    "appliedPolicies": [
                        {
                            "condition": "branch == main",
                            "matched": true,
                            "affectedModules": [
                                "m"
                            ]
                        }
                    ],
                    "skippedRules": [
                        {
                            "ruleId": "draftPrevention",
                            "reason": null,
                            "category": "WHEN_CONDITION"
                        },
                        {
                            "ruleId": "labels",
                            "reason": "bail",
                            "category": "BAIL_ON_FAIL"
                        }
                    ]
                }
                """.trimIndent(),
                body,
            )
        }

    @Test
    fun `severity mix keeps order and counts and leaves absent fields null`() =
        runTest {
            val findings =
                listOf(
                    Finding(severity = Severity.INFO, message = "i", rule = "r1"),
                    Finding(severity = Severity.WARNING, message = "w", rule = "r2", location = Location.FileRef("b.kt", null)),
                    Finding(severity = Severity.ERROR, message = "e", rule = "r3"),
                )

            val body = module.format(createTestReport(findings = findings), JsonObject(emptyMap())).body

            val compact = body.lines().joinToString("") { it.trim() }
            assertEquals(
                "{\"schemaVersion\": 1,\"passed\": false,\"findings\": [" +
                    "{\"severity\": \"info\",\"message\": \"i\",\"rule\": \"r1\",\"location\": null,\"suggestion\": null," +
                    "\"explanation\": null,\"metadata\": null,\"attachments\": null}," +
                    "{\"severity\": \"warning\",\"message\": \"w\",\"rule\": \"r2\",\"location\": {\"file\": \"b.kt\",\"line\": null}," +
                    "\"suggestion\": null,\"explanation\": null,\"metadata\": null,\"attachments\": null}," +
                    "{\"severity\": \"error\",\"message\": \"e\",\"rule\": \"r3\",\"location\": null,\"suggestion\": null," +
                    "\"explanation\": null,\"metadata\": null,\"attachments\": null}" +
                    "],\"summary\": {\"errors\": 1,\"warnings\": 1,\"info\": 1}}",
                compact,
            )
        }

    @Test
    fun `a fix hint adds typed expectedFormat and mismatch fields`() =
        runTest {
            val finding =
                Finding(
                    severity = Severity.ERROR,
                    message = "bad title",
                    rule = "titleFormat",
                    suggestion = "rename it",
                    metadata = mapOf(
                        WellKnownMetadataKeys.MISMATCH to Mismatch("feat add x", 4, ":").toMetadataValue(),
                        WellKnownMetadataKeys.EXPECTED_FORMAT to "\"feat\" \": \" .+",
                    ),
                )

            val body = module.format(createTestReport(findings = listOf(finding)), JsonObject(emptyMap())).body

            val compact = body.lines().joinToString("") { it.trim() }
            val expected =
                "\"attachments\": null,\"expectedFormat\": \"\\\"feat\\\" \\\": \\\" .+\"," +
                    "\"mismatch\": {\"value\": \"feat add x\",\"position\": 4,\"expected\": \":\"}}"
            assertTrue(expected in compact, compact)
        }

    @Test
    fun `a finding error code is rendered and an absent one is omitted`() =
        runTest {
            val coded = createTestFinding(ruleId = "regex", message = "bad").copy(errorCode = "E0003")
            val plain = createTestFinding(ruleId = "plain", message = "p")
            val body = module.format(createTestReport(findings = listOf(coded, plain)), JsonObject(emptyMap())).body
            val findings = Json
                .parseToJsonElement(body)
                .jsonObject
                .getValue("findings")
                .jsonArray
                .map { it.jsonObject }

            assertEquals("\"E0003\"", findings[0].getValue("errorCode").toString())
            assertTrue("errorCode" !in findings[1])
        }
}
