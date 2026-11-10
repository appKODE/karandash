package io.karandash.plugins.ai.pipeline

import io.karandash.crypto.sha256Hex
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.reporters.CheckRunAnnotationBuilder
import io.karandash.plugins.ai.core.reporters.CodeAnnotation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * `reporters.checkRunAnnotation`: file-located findings as check-run annotations.
 *
 * `format: github` (default) renders the GitHub check-run `output` object (title, summary, at most 50 annotations; the
 * title and the summary in `report.language`, the conclusion and the annotation levels stay GitHub's values);
 * `format: gitlab` renders a GitLab Code Quality report. `consolidate: true` merges findings on the same file and line.
 */
internal object CheckRunAnnotationReport {
    const val REPORT_ID = "check-run-annotations"

    /** GitHub accepts at most 50 annotations per check-run update. */
    private const val GITHUB_ANNOTATION_LIMIT = 50
    private const val FORMAT_GITLAB = "gitlab"

    fun render(
        findings: List<Finding>,
        options: JsonObject,
        language: String? = null,
    ): String {
        val consolidate = (options["consolidate"] as? JsonPrimitive)?.booleanOrNull == true
        val annotations = CheckRunAnnotationBuilder.build(findings).let { if (consolidate) consolidate(it) else it }
        val format = (options["format"] as? JsonPrimitive)?.contentOrNull?.lowercase()
        return if (format == FORMAT_GITLAB) gitlab(annotations).toString() else github(findings, annotations, language).toString()
    }

    private fun consolidate(annotations: List<CodeAnnotation>): List<CodeAnnotation> =
        annotations.groupBy { it.path to it.line }.values.map { group ->
            val worst = group.minBy { it.rawSeverity.ordinal }
            worst.copy(
                title = group.map { it.title }.distinct().joinToString(", "),
                message = group.joinToString("\n") { it.message },
            )
        }

    private fun github(
        findings: List<Finding>,
        annotations: List<CodeAnnotation>,
        language: String?,
    ): JsonObject {
        val summary = CheckRunAnnotationBuilder.summary(findings)
        return buildJsonObject {
            put("title", GeneratedReportText.CHECK_RUN_TITLE.of(language))
            put("conclusion", summary.conclusion)
            put("summary", GeneratedReportText.CHECK_RUN_COUNTS.of(language, summary.errors, summary.warnings, annotations.size))
            put(
                "annotations",
                JsonArray(
                    annotations.take(GITHUB_ANNOTATION_LIMIT).map { a ->
                        buildJsonObject {
                            put("path", a.path)
                            put("start_line", a.line ?: 1)
                            put("end_line", a.line ?: 1)
                            put("annotation_level", a.severity)
                            put("title", a.title)
                            put("message", a.message)
                        }
                    },
                ),
            )
        }
    }

    private fun gitlab(annotations: List<CodeAnnotation>): JsonArray =
        buildJsonArray {
            for (a in annotations) {
                add(
                    buildJsonObject {
                        put("description", a.message)
                        put("check_name", a.title)
                        put("fingerprint", sha256Hex("${a.path}:${a.line}:${a.title}:${a.message}"))
                        put("severity", gitlabSeverity(a.rawSeverity))
                        putJsonObject("location") {
                            put("path", a.path)
                            putJsonObject("lines") { put("begin", a.line ?: 1) }
                        }
                    },
                )
            }
        }

    private fun gitlabSeverity(severity: Severity): String =
        when (severity) {
            Severity.ERROR -> "major"
            Severity.WARNING -> "minor"
            Severity.INFO -> "info"
        }
}
