package io.karandash.helpers.reporter

import io.karandash.api.finding.FindingSummary
import kotlinx.serialization.Serializable

@Serializable
data class ReporterSummary(
    val errors: Int,
    val warnings: Int,
    val info: Int,
)

fun FindingSummary.toReporterSummary(): ReporterSummary = ReporterSummary(errors = errors, warnings = warnings, info = info)
