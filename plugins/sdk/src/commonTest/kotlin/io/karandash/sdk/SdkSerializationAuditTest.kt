package io.karandash.sdk

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Audit: every public plain data class exposed to plugins (core:api, core:models, plugins:sdk) is `@Serializable`.
 *
 * A missing annotation fails compilation of this file. Runtime context holders (lambdas, Throwable, live
 * services) are the documented exception and are deliberately absent - see docs/development/conventions.md "@Serializable Contract".
 * Add new public data classes here.
 */
class SdkSerializationAuditTest {
    private val descriptors: List<SerialDescriptor> =
        listOf(
            serializer<io.karandash.api.ai.AiCompletionRequest>().descriptor,
            serializer<io.karandash.api.ai.AiCompletionResponse>().descriptor,
            serializer<io.karandash.api.ai.AiContextBlock>().descriptor,
            serializer<io.karandash.api.ai.BlockBudget>().descriptor,
            serializer<io.karandash.api.ai.MetricResult>().descriptor,
            serializer<io.karandash.api.ai.ReportResult>().descriptor,
            serializer<io.karandash.api.config.ConfigField>().descriptor,
            serializer<io.karandash.api.config.ConfigProperty>().descriptor,
            serializer<io.karandash.api.config.ConfigSchemaError>().descriptor,
            serializer<io.karandash.api.context.PrecomputedDiffContext>().descriptor,
            serializer<io.karandash.api.context.ValidationContext>().descriptor,
            serializer<io.karandash.api.errors.ErrorCode>().descriptor,
            serializer<io.karandash.api.finding.BaselineComparison>().descriptor,
            serializer<io.karandash.api.finding.ComplianceItem>().descriptor,
            serializer<io.karandash.api.finding.FindingFeedback>().descriptor,
            serializer<io.karandash.api.finding.FindingSummary>().descriptor,
            serializer<io.karandash.api.finding.FlatLocation>().descriptor,
            serializer<io.karandash.api.finding.FormattedReport>().descriptor,
            serializer<io.karandash.api.finding.InlineCommentData>().descriptor,
            serializer<io.karandash.api.finding.LocationKey>().descriptor,
            serializer<io.karandash.api.finding.ModuleMessage>().descriptor,
            serializer<io.karandash.api.finding.ReportMetadata>().descriptor,
            serializer<io.karandash.api.finding.ReviewAward>().descriptor,
            serializer<io.karandash.api.finding.ReviewNote>().descriptor,
            serializer<io.karandash.api.finding.ReviewThread>().descriptor,
            serializer<io.karandash.api.finding.ReviewThreadPosition>().descriptor,
            serializer<io.karandash.api.finding.RuleIdentifier>().descriptor,
            serializer<io.karandash.api.finding.RuleSummary>().descriptor,
            serializer<io.karandash.api.finding.SkippedRuleInfo>().descriptor,
            serializer<io.karandash.api.finding.TimingData>().descriptor,
            serializer<io.karandash.api.finding.ValidationMetrics>().descriptor,
            serializer<io.karandash.api.finding.ValidationReport>().descriptor,
            serializer<io.karandash.api.module.ModuleDocumentation>().descriptor,
            serializer<io.karandash.api.module.ModuleUsage>().descriptor,
            serializer<io.karandash.api.platform.CIEnvironment>().descriptor,
            serializer<io.karandash.api.platform.ChargeResult.Accepted>().descriptor,
            serializer<io.karandash.api.platform.ChargeResult.RejectedCount>().descriptor,
            serializer<io.karandash.api.platform.ChargeResult.RejectedTime>().descriptor,
            serializer<io.karandash.api.platform.FetchOptions>().descriptor,
            serializer<io.karandash.api.platform.MergeOptions>().descriptor,
            serializer<io.karandash.api.platform.ParsedMergeRequestUrl>().descriptor,
            serializer<io.karandash.api.platform.PatternParam>().descriptor,
            serializer<io.karandash.api.platform.ProviderDetectionScore>().descriptor,
            serializer<io.karandash.api.platform.RateLimitInfo>().descriptor,
            serializer<io.karandash.models.ActivePolicy>().descriptor,
            serializer<io.karandash.models.Approval>().descriptor,
            serializer<io.karandash.models.BlameCommit>().descriptor,
            serializer<io.karandash.models.BlameEntry>().descriptor,
            serializer<io.karandash.models.CiJobStatus>().descriptor,
            serializer<io.karandash.models.Commit>().descriptor,
            serializer<io.karandash.models.CommitAuthor>().descriptor,
            serializer<io.karandash.models.DiffRefs>().descriptor,
            serializer<io.karandash.models.FileChange>().descriptor,
            serializer<io.karandash.models.MergeRequest>().descriptor,
            serializer<io.karandash.models.User>().descriptor,
            serializer<io.karandash.models.finding.Attachment>().descriptor,
            serializer<io.karandash.models.finding.Finding>().descriptor,
            serializer<io.karandash.models.finding.FixSuggestion.AddLabel>().descriptor,
            serializer<io.karandash.models.finding.FixSuggestion.Custom>().descriptor,
            serializer<io.karandash.models.finding.FixSuggestion.RemoveLabel>().descriptor,
            serializer<io.karandash.models.finding.FixSuggestion.SetDescription>().descriptor,
            serializer<io.karandash.models.finding.FixSuggestion.SetDraft>().descriptor,
            serializer<io.karandash.models.finding.FixSuggestion.SetTitle>().descriptor,
            serializer<io.karandash.models.finding.Location.CommitRef>().descriptor,
            serializer<io.karandash.models.finding.Location.FileRef>().descriptor,
            serializer<io.karandash.models.finding.Mismatch>().descriptor,
            serializer<io.karandash.sdk.content.ContentSource.Inline>().descriptor,
            serializer<io.karandash.sdk.content.ContentSource.LocalFile>().descriptor,
            serializer<io.karandash.sdk.content.ContentSource.Multiple>().descriptor,
            serializer<io.karandash.sdk.content.ContentSource.RemoteFile>().descriptor,
            serializer<io.karandash.sdk.content.ContentSource.RepoFile>().descriptor,
            serializer<io.karandash.sdk.content.DirectorySource.LocalDir>().descriptor,
            serializer<io.karandash.sdk.content.DirectorySource.RemoteDir>().descriptor,
            serializer<io.karandash.sdk.content.DirectorySource.RepoDir>().descriptor,
            io.karandash.api.ModuleResult.Success
                .serializer(String.serializer())
                .descriptor,
        )

    @Test
    fun `every audited data class has a generated serializer`() {
        assertTrue(descriptors.all { it.serialName.isNotBlank() })
    }

    @Test
    fun `audited serial names are unique`() {
        val names = descriptors.map { it.serialName }
        assertEquals(
            names.size,
            names.toSet().size,
            names
                .groupBy { it }
                .filterValues { it.size > 1 }
                .keys
                .toString()
        )
    }
}
