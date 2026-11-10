package io.karandash.sdk.finding

/**
 * Re-exports of report types from `core-api`.
 *
 * The canonical implementations now live in `core-api` so that `core/engine/`
 * can build reports without depending on the full SDK.
 */
public typealias ValidationReport = io.karandash.api.finding.ValidationReport
public typealias LocationKey = io.karandash.api.finding.LocationKey
public typealias ComplianceItem = io.karandash.api.finding.ComplianceItem
public typealias ComplianceStatus = io.karandash.api.finding.ComplianceStatus
public typealias ReportMetadata = io.karandash.api.finding.ReportMetadata
public typealias FormattedReport = io.karandash.api.finding.FormattedReport
public typealias ValidationMetrics = io.karandash.api.finding.ValidationMetrics
public typealias RuleSummary = io.karandash.api.finding.RuleSummary
public typealias BaselineComparison = io.karandash.api.finding.BaselineComparison
