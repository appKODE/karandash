package io.karandash.plugins.formats

import io.karandash.Version
import io.karandash.api.ReporterConfig
import io.karandash.api.config.PreserveKeys
import io.karandash.api.errors.KarandashError
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.ValidationReport
import io.karandash.helpers.reporter.PROJECT_URL
import io.karandash.helpers.reporter.facts
import io.karandash.helpers.reporter.plainTextBody
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.finding.codeChange
import io.karandash.models.finding.groupedByFingerprint
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.typed.AbstractTypedFormatModule
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Configuration for [SarifFormatModule].
 *
 * @property version Overrides the tool version in the SARIF output. Defaults to [Version.VERSION].
 * @property language Language of the fix-hint labels; set from `report.language` by the pipeline.
 */
@Serializable
data class SarifFormatConfig(
    val version: String? = null,
    val language: String? = null,
) : ReporterConfig

/**
 * Pure formatter that converts a [ValidationReport] into SARIF 2.1.0 JSON.
 *
 * Extracts the SARIF generation logic from `SarifReporterModule`. No I/O is performed --
 * the resulting [FormattedReport.body] is delivered by an output module.
 */
class SarifFormatModule : AbstractTypedFormatModule<SarifFormatConfig>(SarifFormatConfig.serializer()) {
    override val id: String = ID
    override val name: String = "SARIF Format"
    override val version: String = Version.VERSION
    override val formatId: String = FORMAT_ID
    override val hoverDocumentation: String = HOVER_DOCUMENTATION

    override val description: String =
        "Formats validation results in SARIF 2.1.0 for GitHub Code Scanning and other SAST tools."

    companion object {
        /** Module ID for registry lookups. */
        const val ID = "io.karandash.format.sarif"

        /** Format identifier used in [FormattedReport.formatId]. */
        const val FORMAT_ID = "sarif"

        /** `partialFingerprints` key: the finding fingerprint, versioned so a changed algorithm starts a new series. */
        private const val FINGERPRINT_KEY = "karandashFingerprint/v1"
    }

    override suspend fun formatTyped(
        report: ValidationReport,
        config: SarifFormatConfig,
    ): FormattedReport {
        val toolVersion = config.version?.takeIf { it.isNotBlank() } ?: Version.VERSION
        val rules = buildRules(report)
        val repeated = report.findings
            .groupedByFingerprint()
            .filterValues { it.size > 1 }
            .keys
        val seen = mutableMapOf<String, Int>()
        val results = report.findings.map { finding ->
            // SARIF wants a fingerprint to name one result: the 2nd and later findings sharing it get a ":n" occurrence suffix.
            val unique = finding.fingerprint?.takeIf { it in repeated }?.let { fp ->
                val n = (seen[fp] ?: 0) + 1
                seen[fp] = n
                finding.copy(fingerprint = if (n == 1) fp else "$fp:$n")
            }
            buildSarifResult(unique ?: finding, config.language)
        }
        val sarifLog = buildSarifLog(toolVersion, rules, results)
        val body = KarandashJson.PrettyWithDefaults.encodeToString(sarifLog)
        return FormattedReport(formatId = FORMAT_ID, body = body)
    }

    private fun buildRules(report: ValidationReport): List<SarifRule> =
        report.findings.map { it.rule }.distinct().map { ruleId ->
            val code = report.findings.firstNotNullOfOrNull { f -> f.errorCode?.takeIf { f.rule == ruleId } }
            SarifRule(
                id = ruleId,
                shortDescription = SarifMessage(text = "Rule: $ruleId"),
                fullDescription = SarifMessage(
                    text = report.explanations[ruleId]
                        ?: report.findings.firstNotNullOfOrNull { f -> f.explanation?.takeIf { f.rule == ruleId } }
                        ?: "Validation rule: $ruleId",
                ),
                helpUri = code?.let { KarandashError.fromCode(it)?.docsUrl },
            )
        }

    private fun buildSarifLog(
        toolVersion: String,
        rules: List<SarifRule>,
        results: List<SarifResult>,
    ): SarifLog =
        SarifLog(
            version = "2.1.0",
            schema = "https://json.schemastore.org/sarif-2.1.0.json",
            runs = listOf(
                SarifRun(
                    tool = SarifTool(
                        driver = SarifToolComponent(
                            name = "karandash",
                            version = toolVersion,
                            informationUri = PROJECT_URL,
                            rules = rules,
                        ),
                    ),
                    results = results,
                    invocations = listOf(SarifInvocation(executionSuccessful = true)),
                ),
            ),
        )

    private fun buildSarifResult(
        finding: Finding,
        language: String?,
    ): SarifResult {
        // the fingerprint has its own place in partialFingerprints
        val properties = finding.metadata + listOfNotNull(finding.errorCode?.let { "errorCode" to it }) +
            finding.facts().filter { it.first != "fingerprint" }
        return SarifResult(
            ruleId = finding.rule,
            level = finding.severity.toSarifLevel(),
            message = SarifMessage(text = finding.plainTextBody(language)),
            locations = buildSarifLocations(finding),
            partialFingerprints = finding.fingerprint?.let { mapOf(FINGERPRINT_KEY to it) },
            properties = properties.ifEmpty { null },
            fixes = buildSarifFixes(finding),
        )
    }

    /** The finding's code change as one SARIF fix; its lines are the producer's hint, a consumer verifies them. */
    private fun buildSarifFixes(finding: Finding): List<SarifFix>? =
        finding.codeChange()?.let { change ->
            val replacement = SarifReplacement(
                deletedRegion = SarifRegion(startLine = change.startLine, endLine = change.endLine),
                insertedContent = SarifArtifactContent(text = change.suggestedCode),
            )
            val description = finding.suggestion ?: finding.message
            listOf(
                SarifFix(SarifMessage(description), listOf(SarifArtifactChange(SarifArtifactLocation(change.path), listOf(replacement))))
            )
        }

    private fun buildSarifLocations(finding: Finding): List<SarifLocation> =
        when (val loc = finding.location) {
            is Location.FileRef -> listOf(
                SarifLocation(
                    physicalLocation = SarifPhysicalLocation(
                        artifactLocation = SarifArtifactLocation(uri = loc.path),
                        region = loc.line?.let { line -> SarifRegion(startLine = line, endLine = loc.endLine?.takeIf { it > line }) },
                    ),
                ),
            )

            is Location.CommitRef -> listOf(
                SarifLocation(
                    physicalLocation = SarifPhysicalLocation(
                        artifactLocation = SarifArtifactLocation(uri = "commit:${loc.sha}"),
                    ),
                ),
            )

            null -> emptyList()
        }

    private fun Severity.toSarifLevel(): String =
        io.karandash.models.finding.SeverityFormat
            .toSarif(this)
}

@Serializable
internal data class SarifLog(
    val version: String,
    @SerialName("\$schema")
    val schema: String,
    val runs: List<SarifRun>,
)

@Serializable
internal data class SarifRun(
    val tool: SarifTool,
    val results: List<SarifResult>,
    val invocations: List<SarifInvocation> = emptyList(),
)

@Serializable
internal data class SarifInvocation(
    val executionSuccessful: Boolean,
)

@Serializable
internal data class SarifTool(
    val driver: SarifToolComponent,
)

/** SARIF 2.1.0 forbids null for these optional objects, so an absent value is omitted. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class SarifToolComponent(
    val name: String,
    val version: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val informationUri: String? = null,
    val rules: List<SarifRule> = emptyList(),
)

/** SARIF 2.1.0 forbids null for these optional objects, so an absent value is omitted. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class SarifRule(
    val id: String,
    val shortDescription: SarifMessage,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val fullDescription: SarifMessage? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val helpUri: String? = null,
)

/** SARIF 2.1.0 forbids null for these optional objects, so an absent value is omitted. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class SarifResult(
    val ruleId: String,
    val level: String,
    val message: SarifMessage,
    val locations: List<SarifLocation> = emptyList(),
    @PreserveKeys
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val partialFingerprints: Map<String, String>? = null,
    @PreserveKeys
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val properties: Map<String, String>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val fixes: List<SarifFix>? = null,
)

/** A SARIF `fix`: what to change, as artifact changes. */
@Serializable
internal data class SarifFix(
    val description: SarifMessage,
    val artifactChanges: List<SarifArtifactChange>,
)

@Serializable
internal data class SarifArtifactChange(
    val artifactLocation: SarifArtifactLocation,
    val replacements: List<SarifReplacement>,
)

@Serializable
internal data class SarifReplacement(
    val deletedRegion: SarifRegion,
    val insertedContent: SarifArtifactContent,
)

@Serializable
internal data class SarifArtifactContent(
    val text: String,
)

@Serializable
internal data class SarifLocation(
    val physicalLocation: SarifPhysicalLocation,
)

/** SARIF 2.1.0 forbids null for these optional objects, so an absent value is omitted. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class SarifPhysicalLocation(
    val artifactLocation: SarifArtifactLocation,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val region: SarifRegion? = null,
)

@Serializable
internal data class SarifArtifactLocation(
    val uri: String,
)

/** SARIF 2.1.0 forbids null for these optional objects, so an absent value is omitted. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class SarifRegion(
    val startLine: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val endLine: Int? = null,
)

@Serializable
internal data class SarifMessage(
    val text: String,
)

/** LSP hover text for this module's config block. */
private val HOVER_DOCUMENTATION = """
    # sarif

    **SARIF Reporter**

    Configure SARIF format output for validation results.

    ## Properties
    - `outputPath: String` - Output file path (required)

    ## Example
    ```
    sarif {
        outputPath = "karandash-results.sarif"
    }
    ```
""".trimIndent()
