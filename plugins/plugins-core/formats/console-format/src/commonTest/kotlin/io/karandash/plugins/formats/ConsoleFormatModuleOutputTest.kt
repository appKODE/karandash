package io.karandash.plugins.formats

import io.karandash.api.ReporterConfig
import io.karandash.api.finding.SkipCategory
import io.karandash.api.finding.SkippedRuleInfo
import io.karandash.models.ActivePolicy
import io.karandash.models.finding.Attachment
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FindingCategory
import io.karandash.models.finding.Location
import io.karandash.models.finding.Mismatch
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.sdk.config.deserializeConfig
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestReport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsoleFormatModuleOutputTest {
    private val module = ConsoleFormatModule()

    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private val full = obj("style" to JsonPrimitive("full"))

    private fun policy(
        name: String?,
        condition: String,
        matched: Boolean,
    ) = ActivePolicy(name, condition, matched, emptyList(), forcedEnabled = null, forcedSeverity = null, configOverrides = null)

    private val mixed =
        listOf(
            Finding(
                severity = Severity.ERROR,
                message = "bad \"title\" <x>",
                rule = "titleFormat",
                suggestion = "fix it",
                location = Location.FileRef("src/A.kt", 7),
                explanation = "finding explanation",
                metadata = mapOf("k" to "v"),
            ),
            Finding(severity = Severity.WARNING, message = "big", rule = "sizeLimits", location = Location.CommitRef("abc123")),
            Finding(severity = Severity.INFO, message = "fyi", rule = "labels", location = Location.FileRef("b.kt")),
            Finding(
                severity = Severity.INFO,
                message = "Summary of changes",
                rule = "mrSummary",
                metadata = mapOf(WellKnownMetadataKeys.IS_MR_SUMMARY to "true"),
            ),
        )

    @Test
    fun `empty report says no issues`() =
        runTest {
            val report = module.format(createTestReport(findings = emptyList()), JsonObject(emptyMap()))

            assertEquals("console", report.formatId)
            assertEquals("No issues found\n", report.body)
        }

    @Test
    fun `severity mix renders sections summary and status`() =
        runTest {
            val body = module.format(createTestReport(findings = mixed), full).body

            assertEquals(
                """
                Found 4 issue(s):

                Errors (1):
                  - [titleFormat] bad "title" <x>
                    How to fix: fix it
                    k: v
                    Location: src/A.kt:7
                    Explanation: finding explanation

                Warnings (1):
                  - [sizeLimits] big
                    Location: commit:abc123

                Info (1):
                  - [labels] fyi
                    Location: b.kt

                Changes summary:

                Summary of changes

                Validation FAILED

                """.trimIndent(),
                body,
            )
        }

    @Test
    fun `concise groups findings by file and ends with the summary line`() =
        runTest {
            val body = module.format(createTestReport(findings = mixed), JsonObject(emptyMap())).body

            assertEquals(
                """
                src/A.kt
                  error      7  bad "title" <x>  titleFormat
                                How to fix: fix it
                                Why: finding explanation

                commit:abc123
                  warn          big  sizeLimits

                b.kt
                  info          fyi  labels

                Changes summary:

                Summary of changes

                Validation FAILED · 1 error · 1 warning · 1 info

                """.trimIndent(),
                body,
            )
        }

    @Test
    fun `concise colours only when color is set`() =
        runTest {
            val plain = module.format(createTestReport(findings = mixed), JsonObject(emptyMap())).body
            val colored = module.format(createTestReport(findings = mixed), obj("color" to JsonPrimitive(true))).body

            assertTrue("\u001b[" !in plain, plain)
            assertTrue("\u001b[31mValidation FAILED\u001b[0m" in colored, colored)
        }

    @Test
    fun `quiet prints only errors and nothing when clean`() =
        runTest {
            val quiet = obj("quiet" to JsonPrimitive(true))

            val body = module.format(createTestReport(findings = mixed), quiet).body
            assertTrue(body.startsWith("Errors (1):\n  - [titleFormat] bad"), body)
            assertTrue("Warnings" !in body && "Validation" !in body, body)

            val clean = module.format(createTestReport(findings = mixed.drop(1)), quiet).body
            assertEquals("", clean)
        }

    @Test
    fun `verbose prints rule and metadata lines`() =
        runTest {
            val body = module.format(createTestReport(findings = mixed.take(1)), obj("verbose" to JsonPrimitive(true))).body

            assertTrue(body.contains("    Rule: titleFormat\n    k: v\n"), body)
        }

    @Test
    fun `verbose prints the related findings`() =
        runTest {
            val finding = mixed.first().copy(metadata = emptyMap(), relatedFindings = listOf("fp-1", "fp-2"))
            val body = module.format(createTestReport(findings = listOf(finding)), obj("verbose" to JsonPrimitive(true))).body

            assertTrue(body.contains("    relatedFindings: fp-1, fp-2\n"), body)
        }

    @Test
    fun `verbose prints a category count line and quiet runs do not`() =
        runTest {
            val findings = listOf(
                mixed[0].copy(category = FindingCategory.SECURITY),
                mixed[1].copy(category = FindingCategory.STYLE),
                mixed[1].copy(category = FindingCategory.SECURITY),
            )
            val verbose = module.format(createTestReport(findings = findings), obj("verbose" to JsonPrimitive(true))).body
            val plain = module.format(createTestReport(findings = findings), obj()).body

            assertTrue(verbose.contains("Categories: STYLE 1, SECURITY 2\n"), verbose)
            assertTrue("Categories:" !in plain, plain)
        }

    @Test
    fun `console format config decodes as a ReporterConfig`() {
        val config: ReporterConfig = deserializeConfig<ConsoleFormatConfig>(obj("verbose" to JsonPrimitive(true)))

        assertEquals(ConsoleFormatConfig(verbose = true), config)
    }

    @Test
    fun `verbose prints the catalog title and docs link of a finding error code`() =
        runTest {
            val finding = mixed.first().copy(metadata = emptyMap(), errorCode = "E0200")
            val body = module.format(createTestReport(findings = listOf(finding)), obj("verbose" to JsonPrimitive(true))).body

            assertTrue(
                body.contains(
                    "    Rule: titleFormat\n    Error: E0200 Title format mismatch " +
                        "(https://appkode.github.io/karandash/reference/error-codes/#e0200)\n",
                ),
                body,
            )
        }

    private val fixHint =
        Finding(
            severity = Severity.ERROR,
            message = "MR title does not match the required format",
            rule = "titleFormat",
            suggestion = "rename the title to `feat: add x`",
            metadata = mapOf(
                WellKnownMetadataKeys.MISMATCH to Mismatch("feat add x", 4, "\": \"").toMetadataValue(),
                WellKnownMetadataKeys.EXPECTED_FORMAT to "(\"feat\" | \"fix\") \": \" .+",
                WellKnownMetadataKeys.FORMAT_EXPLAIN to "A type, a colon and a space, then the subject.",
                WellKnownMetadataKeys.FORMAT_DIAGRAM to "─┬─ \"feat\" ─┬─ \": \" ─ .+ ─\n └─ \"fix\" ──┘",
            ),
        )

    @Test
    fun `a fix hint prints labelled Where How to fix and Format lines`() =
        runTest {
            val body = module.format(createTestReport(findings = listOf(fixHint)), full).body

            assertTrue(
                body.contains(
                    """
                    |  - [titleFormat] MR title does not match the required format
                    |    Where: `feat add x` — after `feat` expected `": "` — found whitespace
                    |    How to fix: rename the title to `feat: add x`
                    |    Format: `("feat" | "fix") ": " .+`
                    |    A type, a colon and a space, then the subject.
                    |
                    """.trimMargin(),
                ),
                body,
            )
            assertTrue("mismatch" !in body && "┬" !in body, body)
        }

    @Test
    fun `verbose adds the format diagram and keeps fix hint keys out of the metadata dump`() =
        runTest {
            val body = module.format(createTestReport(findings = listOf(fixHint)), obj("verbose" to JsonPrimitive(true))).body

            assertTrue(body.contains("    Format diagram:\n      ─┬─ \"feat\" ─┬─ \": \" ─ .+ ─\n       └─ \"fix\" ──┘\n"), body)
            assertTrue("    mismatch:" !in body && "    expectedFormat:" !in body && "    formatExplain:" !in body, body)
        }

    @Test
    fun `explain prefers report then initialize explanations then finding`() =
        runTest {
            val explain = obj("explain" to JsonPrimitive(true))
            val finding = mixed.first()

            val fromFinding = module.format(createTestReport(findings = listOf(finding)), explain).body
            assertTrue(fromFinding.contains("    Explanation: finding explanation\n"), fromFinding)

            module.initialize(TestPluginContext(pluginConfig = obj("explanations" to obj("titleFormat" to JsonPrimitive("from config")))))
            val fromInit = module.format(createTestReport(findings = listOf(finding)), explain).body
            assertTrue(fromInit.contains("    Explanation: from config\n"), fromInit)

            val report = createTestReport(findings = listOf(finding)).copy(explanations = mapOf("titleFormat" to "from report"))
            val fromReport = module.format(report, explain).body
            assertTrue(fromReport.contains("    Explanation: from report\n"), fromReport)
        }

    @Test
    fun `attachments print filename and content`() =
        runTest {
            val finding = Finding(severity = Severity.WARNING, message = "m", rule = "r", attachments = listOf(Attachment("a.md", "# hi")))

            val body = module.format(createTestReport(findings = listOf(finding)), full).body

            assertTrue(body.contains("    Attachment: a.md\n# hi\n"), body)
            assertTrue(body.endsWith("Validation PASSED\n"), body)
        }

    @Test
    fun `policies and skipped rules use configured marks`() =
        runTest {
            val report =
                createTestReport(findings = emptyList()).copy(
                    appliedPolicies =
                        listOf(
                            policy(null, "branch == main", matched = true),
                            policy("Release", "x", matched = false),
                        ),
                    skippedRules =
                        listOf(
                            SkippedRuleInfo("labels", "", SkipCategory.WHEN_CONDITION),
                            SkippedRuleInfo("draftPrevention", "bail", SkipCategory.BAIL_ON_FAIL),
                        ),
                )
            val config = obj("successMark" to JsonPrimitive("+"), "failureMark" to JsonPrimitive("-"))

            val body = module.format(report, config).body

            assertEquals(
                """
                No issues found
                Policies (2 evaluated, 1 matched):
                  +  branch == main
                  -  Release
                Skipped rules (2):
                  - labels
                  - draftPrevention — bail


                """.trimIndent(),
                body,
            )
        }
}
