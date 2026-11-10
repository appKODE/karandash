package io.karandash.plugins.formats

import io.karandash.Version
import io.karandash.api.ReporterConfig
import io.karandash.api.finding.CommentMarkers
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.InlineCommentData
import io.karandash.api.finding.ReviewProvenance
import io.karandash.api.finding.ValidationReport
import io.karandash.api.run.RunScopedServices
import io.karandash.api.run.RunServiceKeys
import io.karandash.helpers.reporter.TemplateOverride
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.report.MrView
import io.karandash.models.report.ReportStyle
import io.karandash.platform.createDirectories
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.sdk.PluginContext
import io.karandash.sdk.enrichment.EnrichmentKeys
import io.karandash.sdk.typed.AbstractTypedFormatModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.Serializable

/**
 * Config for [MarkdownFormatModule].
 *
 * @property preview When true, format only: the report carries no comment markers or inline comments,
 *   so platform outputs post nothing while stdout/file outputs still get the body.
 * @property language Output language of the summary strings (BCP-47 tag; `en`, `ru`, `pl`, others render in `en`).
 *   Set from `report.language` by the pipeline; finding messages and inline note tokens are never translated.
 * @property transparency The `report.transparency` keys: provenance block, inline why footer, flow diagram.
 * @property walkthrough The `report.walkthrough` key: `collapsed` shows the `mr-summary` walkthrough (changes by
 *   review unit, and a sequence diagram when units interact) in a collapsed block under the changes summary.
 * @property flowArtifactDir Where every run with provenance writes `review-flow.md` (the Mermaid diagram), whatever
 *   [MarkdownTransparencyConfig.flowDiagram] says; a relative path resolves against the run's working directory.
 *   Empty disables the artifact, and a [preview] run never writes it.
 * @property style The `report.style` preset: `standard` (default) and `quiet` render the compact summary table and
 *   short inline notes; `verbose` renders the full layout every version before 2.x had.
 * @property mr The merge request the report is about, injected by the pipeline when known: its URL makes the
 *   summary's `Where` cells links to the changes page.
 * @property template A Mustache template (usually `{ file: ... }`) that replaces the bundled summary of the `standard`
 *   and `quiet` styles; `{{x}}` is Markdown-escaped. A broken template falls back to the bundled one (warn).
 * @property partials Named partials the [template] includes as `{{>name}}`.
 */
@Serializable
data class MarkdownFormatConfig(
    val preview: Boolean = false,
    val language: String? = null,
    val transparency: MarkdownTransparencyConfig = MarkdownTransparencyConfig(),
    val walkthrough: WalkthroughDisplay = WalkthroughDisplay.OFF,
    val flowArtifactDir: String = MarkdownFormatModule.DEFAULT_FLOW_ARTIFACT_DIR,
    val style: String? = null,
    val mr: MrView? = null,
    val template: String? = null,
    val partials: Map<String, String> = emptyMap(),
) : ReporterConfig

/**
 * Pure formatter that converts a [ValidationReport] into markdown with inline comments.
 *
 * The resulting [FormattedReport] carries a summary markdown body and structured [InlineCommentData] for platform
 * outputs to post.
 *
 * Formatting helpers are in [MarkdownFormatHelper]. Its one side effect is the `review-flow.md` debug artifact.
 */
class MarkdownFormatModule : AbstractTypedFormatModule<MarkdownFormatConfig>(MarkdownFormatConfig.serializer()) {
    override val id: String = ID
    override val name: String = "Markdown Format"
    override val version: String = Version.VERSION
    override val formatId: String = FORMAT_ID
    override val hoverDocumentation: String = HOVER_DOCUMENTATION

    override val description: String = "Formats validation results as markdown for MR/PR comments."
    override val isDefaultPlatform: Boolean = true

    private var logger: Logger = NoOpLogger
    private var workingDirectory: String = ""

    override suspend fun initialize(context: PluginContext): Boolean {
        logger = context.logger
        workingDirectory = context.workingDirectory
        return true
    }

    override suspend fun formatTyped(
        report: ValidationReport,
        config: MarkdownFormatConfig,
    ): FormattedReport {
        val locale = SummaryLocale.of(config.language)
        val style = ReportStyle.of(config.style)
        val inlineBody: (Finding) -> String = if (style.legacyLayout) {
            { formatInlineCommentBody(it, config.transparency.inlineWhy, locale) }
        } else {
            { formatCompactInlineBody(style.apply(it), it, locale) }
        }
        val inlineComments = if (config.preview) emptyList() else buildInlineComments(report, inlineBody)
        val contributors = RunScopedServices.current()?.get(RunServiceKeys.ENRICHMENT)
        val contributorLine = locale.contributorLine(contributors?.get(EnrichmentKeys.LOCAL_GIT_CONTRIBUTOR_STATS))
        val walkthrough = config.walkthrough == WalkthroughDisplay.COLLAPSED
        val markdown = if (style.legacyLayout) {
            formatSummaryMarkdown(
                report = report,
                summaryFindings = report.summaryOnly,
                inlineCount = inlineComments.size,
                locale = locale,
                transparency = config.transparency,
                walkthrough = walkthrough,
                contributorLine = contributorLine,
            )
        } else {
            val sections = listOf(
                formatAiSections(report, walkthrough, locale),
                boundedProvenanceBlock(report.provenance, config.transparency, locale).trim(),
                contributorLine?.let { "_${it}_" }.orEmpty(),
            ).filter { it.isNotBlank() }.joinToString("\n\n")
            val override = TemplateOverride(config.template, config.partials)
            formatCompactSummary(report, CompactSummaryInput(style, locale, config.mr, sections, override, logger))
        }
        report.provenance?.takeIf { it.hasDetails && config.flowArtifactDir.isNotEmpty() && !config.preview }?.let {
            val dir = Path(config.flowArtifactDir)
            writeFlowArtifact(
                it,
                if (dir.isAbsolute || workingDirectory.isEmpty()) dir else Path(workingDirectory, config.flowArtifactDir),
                locale,
            )
        }

        return FormattedReport(
            formatId = FORMAT_ID,
            body = "$MARKER\n$markdown",
            inlineComments = inlineComments,
            commentMarker = MARKER.takeUnless { config.preview },
            inlineMarker = INLINE_MARKER.takeUnless { config.preview },
            changedFiles = report.changedFiles,
        )
    }

    /** One note per finding, each with its own marker, so a reply or resolve targets exactly one finding. */
    private fun buildInlineComments(
        report: ValidationReport,
        inlineBody: (Finding) -> String,
    ): List<InlineCommentData> =
        report.groupedByLocation.flatMap { (key, groupFindings) ->
            val baseLine = key.line ?: return@flatMap emptyList()
            groupFindings.map { finding ->
                val (effectiveLine, startLine) = resolveLineRange(listOf(finding), baseLine)
                InlineCommentData(
                    filePath = key.path,
                    line = effectiveLine,
                    startLine = startLine,
                    body = "$INLINE_MARKER\n${inlineBody(finding)}",
                )
            }
        }

    /**
     * Writes [provenance]'s flow diagram in [locale] to `<dir>/review-flow.md`; a dir without a `.gitignore` gets one of `*`.
     * A write failure is logged and never fails the report.
     */
    private suspend fun writeFlowArtifact(
        provenance: ReviewProvenance,
        dir: Path,
        locale: SummaryLocale,
    ) = withContext(Dispatchers.IO) {
        try {
            createDirectories(dir)
            val gitignore = Path(dir, ".gitignore")
            if (!SystemFileSystem.exists(gitignore)) SystemFileSystem.sink(gitignore).buffered().use { it.writeString("*\n") }
            SystemFileSystem.sink(Path(dir, FLOW_ARTIFACT_FILE)).buffered().use { it.writeString(flowDiagram(provenance, locale)) }
            logger.debug("review flow diagram: $dir/$FLOW_ARTIFACT_FILE")
        } catch (e: IOException) {
            logger.warn("could not write the review flow diagram to $dir/$FLOW_ARTIFACT_FILE: ${e.message}")
        }
    }

    companion object {
        /** Default [MarkdownFormatConfig.flowArtifactDir]. */
        const val DEFAULT_FLOW_ARTIFACT_DIR = ".karandash/debug"

        /** File name of the flow diagram artifact. */
        const val FLOW_ARTIFACT_FILE = "review-flow.md"

        /** Module ID for registry lookups. */
        const val ID = "io.karandash.format.markdown"

        /** Format identifier used in [FormattedReport.formatId]. */
        const val FORMAT_ID = "markdown"

        /** Marker for summary comment deduplication on platforms. */
        const val MARKER = CommentMarkers.SUMMARY

        /** Marker for inline comment deduplication on platforms. */
        const val INLINE_MARKER = CommentMarkers.INLINE
    }
}

/**
 * Resolves the effective line and optional start line for a multi-line comment range.
 *
 * Platform APIs (GitHub, GitLab) expect `line` to be the end of the range and
 * `startLine` to be the beginning.
 */
internal fun resolveLineRange(
    findings: List<Finding>,
    baseLine: Int,
): Pair<Int, Int?> {
    val maxEndLine = findings
        .mapNotNull { (it.location as? Location.FileRef)?.endLine }
        .maxOrNull()
    return if (maxEndLine != null && maxEndLine > baseLine) maxEndLine to baseLine else baseLine to null
}

/** LSP hover text for this module's config block. */
private val HOVER_DOCUMENTATION = """
    # markdown

    **Markdown Reporter**

    Enable or disable posting validation results as MR/PR comments.

    **Type:** `Boolean`

    ## Example
    ```
    reporters {
        markdown = true
    }
    ```
""".trimIndent()
