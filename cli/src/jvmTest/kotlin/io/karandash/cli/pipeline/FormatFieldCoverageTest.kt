package io.karandash.cli.pipeline

import io.karandash.api.finding.FormattedReport
import io.karandash.api.module.FormatModule
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.validate.splitHideFields
import io.karandash.cli.commands.validate.without
import io.karandash.models.finding.Attachment
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FindingCategory
import io.karandash.models.finding.FindingField
import io.karandash.models.finding.FixSuggestion
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.testfixtures.createTestReport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every [Finding] field is rendered by every format unless the format's medium has no place for it (declared below,
 * with the reason), and `hideFields` takes any hideable field out of every format.
 *
 * A sentinel finding with a unique value in each field goes through every registered format: a declared field must
 * show its sentinel, an N/A field must not. A new Finding field, a new format, or a format that starts or stops
 * rendering a field fails this test until the declaration below is updated. The check runs the `verbose` style, the
 * one that shows every field; `standard` and `quiet` leave fields out by design.
 */
class FormatFieldCoverageTest {
    private val finding = Finding(
        severity = Severity.ERROR,
        message = "Sentinel message",
        rule = "sentinel-rule",
        location = Location.FileRef("src/Sentinel.kt", 4141, 4343),
        explanation = "Sentinel explanation",
        suggestion = "Sentinel suggestion",
        metadata = mapOf("sentinelKey" to "sentinel-value", WellKnownMetadataKeys.SUGGESTED_CODE to "val sentinelCode = 1"),
        fixSuggestions = listOf(FixSuggestion.AddLabel("sentinel-label")),
        attachments = listOf(Attachment("sentinel-attachment.md", "Sentinel attachment body")),
        fingerprint = "fp-sentinel-0001",
        priority = 4242,
        confidence = 0.37f,
        relatedFindings = listOf("related-sentinel"),
        category = FindingCategory.SECURITY,
        errorCode = "E0202",
    )

    /** The text each field shows when rendered; `endLine` and `codeChange` are read from `location` and `metadata`. */
    private val sentinels = mapOf(
        "severity" to "error",
        "message" to "Sentinel message",
        "rule" to "sentinel-rule",
        "location" to "Sentinel.kt",
        "endLine" to "4343",
        "explanation" to "Sentinel explanation",
        "suggestion" to "Sentinel suggestion",
        "metadata" to "sentinel-value",
        "codeChange" to "val sentinelCode = 1",
        "fixSuggestions" to "sentinel-label",
        "attachments" to "Sentinel attachment body",
        "fingerprint" to "fp-sentinel-0001",
        "priority" to "4242",
        "confidence" to "0.37",
        "relatedFindings" to "related-sentinel",
        "category" to "security",
        "errorCode" to "E0202",
    )

    /** The template format renders the report view, which carries no field for these; a user template cannot show them. */
    private val templateNotApplicable: Map<String, String> =
        listOf(
            "metadata",
            "codeChange",
            "fixSuggestions",
            "attachments",
            "fingerprint",
            "priority",
            "confidence",
            "relatedFindings",
            "category",
            "errorCode",
        ).associateWith { "not in the report view" }

    /** A Code Quality issue has a description, check name, severity, location, fingerprint and a markdown body. */
    private val codeQualityNotApplicable: Map<String, String> =
        listOf(
            "metadata",
            "codeChange",
            "fixSuggestions",
            "attachments",
            "priority",
            "confidence",
            "relatedFindings",
            "category",
            "errorCode",
        ).associateWith { "no CodeClimate issue field" }

    /**
     * Fields a format's medium has no place for, with the reason, by format id. Every format renders every field
     * today; `hideFields` is how a config leaves one out.
     */
    private val notApplicable: Map<String, Map<String, String>> =
        listOf("console", "markdown", "junit", "slack", "html", "json", "sarif", "rdjson", "rdjsonl")
            .associateWith { emptyMap<String, String>() } +
            ("template" to templateNotApplicable) +
            ("codequality" to codeQualityNotApplicable)

    private suspend fun formats(): List<FormatModule> {
        val bootstrap = PluginBootstrap(envReader = { null })
        bootstrap.loadPluginsFromDirs(emptyList())
        return bootstrap.registry().formats()
    }

    private val verbose = JsonObject(mapOf("style" to JsonPrimitive("verbose")))

    /** Body and inline comments, without wall-clock timestamps that could contain a numeric sentinel. */
    private fun FormattedReport.text(): String =
        (
            body + inlineComments.joinToString(
                "\n"
            ) { "${it.filePath} ${it.startLine} ${it.line}\n${it.body}" }
        ).replace(Regex("[0-9]{10,}"), "")

    @Test
    fun `every finding field has a sentinel`() {
        val fields = List(Finding.serializer().descriptor.elementsCount, Finding.serializer().descriptor::getElementName)
        assertEquals((fields + "endLine" + "codeChange").toSet(), sentinels.keys)
    }

    @Test
    fun `every format is declared`() =
        runTest {
            assertEquals(notApplicable.keys, formats().map { it.formatId }.toSet())
        }

    @Test
    fun `each format renders every field it does not declare not applicable`() =
        runTest {
            for (format in formats()) {
                val text = format.format(createTestReport(findings = listOf(finding)), verbose).text()
                val na = notApplicable.getValue(format.formatId)
                for ((field, sentinel) in sentinels) {
                    val shown = text.contains(sentinel, ignoreCase = true)
                    if (field in na) {
                        assertTrue(!shown, "${format.formatId} now renders $field; drop it from notApplicable")
                    } else {
                        assertTrue(shown, "${format.formatId} does not render $field; render it or declare it N/A")
                    }
                }
            }
        }

    @Test
    fun `hideFields takes every hideable field out of each format`() =
        runTest {
            // kebab-case and snake_case names, as a user may write them
            val names = FindingField.entries.filter { it.hideable }.map { field ->
                field.key.replace(Regex("[A-Z]")) { "-" + it.value.lowercase() }
            }
            val config = JsonObject(mapOf("hideFields" to JsonArray(names.map(::JsonPrimitive))))
            val (hidden, formatConfig) = splitHideFields(config)
            assertEquals(FindingField.entries.filter { it.hideable }.toSet(), hidden.fields)
            assertEquals(emptyList(), hidden.warnings)
            assertEquals(JsonObject(emptyMap()), formatConfig)
            val report = createTestReport(findings = listOf(finding)).without(hidden.fields)
            for (format in formats()) {
                val text = format.format(report, formatConfig).text()
                for (field in FindingField.entries) {
                    val shown = text.contains(sentinels.getValue(field.key), ignoreCase = true)
                    assertEquals(!field.hideable, shown, "${format.formatId}: ${field.key} shown=$shown")
                }
            }
        }

    @Test
    fun `hideFields warns once about unknown names and about fields that cannot be hidden`() {
        val config = JsonObject(
            mapOf(
                "hideFields" to JsonArray(listOf("error_code", "MESSAGE", "nope", "bogus").map(::JsonPrimitive)),
                "verbose" to JsonPrimitive(true),
            ),
        )
        val (hidden, formatConfig) = splitHideFields(config)
        assertEquals(setOf(FindingField.ERROR_CODE), hidden.fields)
        assertEquals(JsonObject(mapOf("verbose" to JsonPrimitive(true))), formatConfig)
        assertEquals(2, hidden.warnings.size, hidden.warnings.toString())
        assertTrue(hidden.warnings.any { "'message' cannot be hidden" in it })
        val unknown = hidden.warnings.single { "unknown" in it }
        assertTrue("'nope'" in unknown && "'bogus'" in unknown && "endLine" in unknown, unknown)
    }
}
