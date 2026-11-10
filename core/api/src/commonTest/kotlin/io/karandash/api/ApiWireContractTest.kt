package io.karandash.api

import io.karandash.api.finding.BaselineComparison
import io.karandash.api.finding.FindingFeedback
import io.karandash.api.finding.InlineFindingRef
import io.karandash.api.finding.InlineFindingThread
import io.karandash.api.finding.ReviewAward
import io.karandash.api.finding.ReviewNote
import io.karandash.api.finding.ReviewThread
import io.karandash.api.finding.ReviewThreadPosition
import io.karandash.api.finding.RuleSummary
import io.karandash.api.finding.SkipCategory
import io.karandash.api.finding.SkippedRuleInfo
import io.karandash.api.finding.ValidationMetrics
import io.karandash.api.platform.CIEnvironment
import io.karandash.api.platform.FetchOptions
import io.karandash.api.platform.MergeOptions
import io.karandash.api.platform.PlatformOperation
import io.karandash.api.platform.ProviderDetectionScore
import io.karandash.duration.KarandashDuration
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.content.ContentSource
import io.karandash.sdk.content.DirectorySource
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * Wire contract for the API value types: every single-field variant survives a round trip with and
 * without encoded defaults and differs from the base in equality.
 */
class ApiWireContractTest {
    private val sparse = Json(KarandashJson.Default) { encodeDefaults = false }

    private fun <T : Any> assertContract(
        serializer: KSerializer<T>,
        base: T,
        variants: List<T>,
    ) {
        (listOf(base) + variants).forEach { value ->
            listOf(KarandashJson.Default, sparse).forEach { json ->
                assertEquals(value, json.decodeFromString(serializer, json.encodeToString(serializer, value)))
            }
            assertNotEquals<Any?>(value, "other")
            assertEquals(value.hashCode(), value.hashCode())
        }
        variants.forEach { assertNotEquals(base, it) }
        assertEquals(variants.size, variants.toSet().size, "variants must be distinct")
    }

    private val note = ReviewNote("n1", "body")

    @Test
    fun `review thread parts take part in equality and serialization`() {
        val thread = ReviewThread("t1", listOf(note))
        assertContract(
            ReviewThread.serializer(),
            thread,
            listOf(
                thread.copy(id = "t2"),
                thread.copy(notes = emptyList()),
                thread.copy(resolved = true),
                thread.copy(position = ReviewThreadPosition(newPath = "a.kt")),
            ),
        )
        assertContract(
            ReviewNote.serializer(),
            note,
            listOf(
                note.copy(id = "n2"),
                note.copy(body = "b2"),
                note.copy(author = "dev"),
                note.copy(createdAt = "now"),
                note.copy(system = true),
                note.copy(resolvable = true),
                note.copy(resolved = true),
                note.copy(resolvedBy = "dev"),
                note.copy(awards = listOf(ReviewAward("thumbsup"))),
            ),
        )
        val award = ReviewAward("thumbsup")
        assertContract(ReviewAward.serializer(), award, listOf(award.copy(name = "x"), award.copy(user = "dev")))
        val feedback = FindingFeedback(rule = "r", fingerprint = "f", author = "dev")
        assertContract(
            FindingFeedback.serializer(),
            feedback,
            listOf(
                feedback.copy(rule = "r2"),
                feedback.copy(fingerprint = "f2"),
                feedback.copy(reaction = "x"),
                feedback.copy(author = "qa")
            ),
        )
        val position = ReviewThreadPosition()
        assertContract(
            ReviewThreadPosition.serializer(),
            position,
            listOf(
                position.copy(newPath = "a"),
                position.copy(oldPath = "b"),
                position.copy(newLine = 1),
                position.copy(oldLine = 2),
            ),
        )
    }

    @Test
    fun `inline finding refs and threads take part in equality and serialization`() {
        val ref = InlineFindingRef(rule = "r", messageHash = 1)
        assertContract(
            InlineFindingRef.serializer(),
            ref,
            listOf(
                ref.copy(rule = "r2"),
                ref.copy(path = "a.kt"),
                ref.copy(messageHash = 2),
                ref.copy(semanticFingerprint = "fp"),
                ref.copy(messagePrefix = "msg"),
                ref.copy(fpv = "2"),
            ),
        )
        val thread = InlineFindingThread(ReviewThread("t", listOf(note)), note, listOf(ref))
        assertContract(
            InlineFindingThread.serializer(),
            thread,
            listOf(
                thread.copy(thread = ReviewThread("u", emptyList())),
                thread.copy(botNote = note.copy(id = "n9")),
                thread.copy(refs = emptyList()),
            ),
        )
    }

    @Test
    fun `report counters take part in equality and serialization`() {
        val metrics = ValidationMetrics()
        assertContract(
            ValidationMetrics.serializer(),
            metrics,
            listOf(
                metrics.copy(totalDurationMs = 1),
                metrics.copy(ruleExecutionTimes = mapOf("r" to 1L)),
                metrics.copy(enrichmentFetchTimeMs = 1),
                metrics.copy(reportGenerationTimeMs = 1),
                metrics.copy(cacheHits = 1),
                metrics.copy(cacheMisses = 1),
            ),
        )
        val summary = RuleSummary("r")
        assertContract(
            RuleSummary.serializer(),
            summary,
            listOf(
                summary.copy(ruleId = "r2"),
                summary.copy(errors = 1),
                summary.copy(warnings = 1),
                summary.copy(infos = 1),
                summary.copy(hasFixSuggestions = true),
                summary.copy(executionTimeMs = 1),
                summary.copy(fromCache = true),
            ),
        )
        val skipped = SkippedRuleInfo("r")
        assertContract(
            SkippedRuleInfo.serializer(),
            skipped,
            listOf(
                skipped.copy(ruleId = "r2"),
                skipped.copy(reason = "why"),
                skipped.copy(category = SkipCategory.AI_GATED),
                skipped.copy(policy = "strict"),
            ),
        )
        val baseline = BaselineComparison()
        assertContract(
            BaselineComparison.serializer(),
            baseline,
            listOf(
                baseline.copy(baselineTimestamp = 1),
                baseline.copy(newFindings = 1),
                baseline.copy(resolvedFindings = 1),
                baseline.copy(unchangedFindings = 1),
            ),
        )
    }

    @Test
    fun `platform options take part in equality and serialization`() {
        val merge = MergeOptions()
        assertContract(
            MergeOptions.serializer(),
            merge,
            listOf(merge.copy(squash = true), merge.copy(deleteSourceBranch = true), merge.copy(commitMessage = "m")),
        )
        assertContract(FetchOptions.serializer(), FetchOptions(), listOf(FetchOptions(0), FetchOptions(50)))
        listOf(-1, 51).forEach { lines -> assertFailsWith<IllegalArgumentException> { FetchOptions(lines) } }
        val env = CIEnvironment("gitlab", null, null, null, null)
        val score = ProviderDetectionScore("gitlab", 0.5, "env")
        assertContract(
            ProviderDetectionScore.serializer(),
            score,
            listOf(
                score.copy(providerId = "github"),
                score.copy(score = 0.9),
                score.copy(source = "url"),
                score.copy(ciEnvironment = env),
            ),
        )
        assertContract(
            CIEnvironment.serializer(),
            env,
            listOf(
                env.copy(platform = "github"),
                env.copy(projectId = "p"),
                env.copy(mrId = "1"),
                env.copy(token = "t"),
                env.copy(baseUrl = "https://h"),
            ),
        )
    }

    @Test
    fun `content source variants round trip through their own serializers`() {
        val ttl = KarandashDuration.ofMinutes(5)
        val local = ContentSource.LocalFile("a.md")
        assertContract(
            ContentSource.LocalFile.serializer(),
            local,
            listOf(local.copy(path = "b.md"), local.copy(auth = "a"), local.copy(resolution = "repo")),
        )
        val remote = ContentSource.RemoteFile("https://h/a.md")
        assertContract(
            ContentSource.RemoteFile.serializer(),
            remote,
            listOf(remote.copy(url = "https://h/b.md"), remote.copy(auth = "a"), remote.copy(cacheTtl = ttl)),
        )
        val repo = ContentSource.RepoFile("a.md")
        assertContract(ContentSource.RepoFile.serializer(), repo, listOf(repo.copy(path = "b"), repo.copy(ref = "main")))
        val multiple = ContentSource.Multiple(listOf(ContentSource.Inline("x")))
        assertContract(
            ContentSource.Multiple.serializer(),
            multiple,
            listOf(multiple.copy(sources = emptyList()), multiple.copy(separator = "-")),
        )
        assertContract(ContentSource.Inline.serializer(), ContentSource.Inline("x"), listOf(ContentSource.Inline("y")))
        val remoteDir = DirectorySource.RemoteDir("https://h/d")
        assertContract(
            DirectorySource.RemoteDir.serializer(),
            remoteDir,
            listOf(remoteDir.copy(url = "https://h/e"), remoteDir.copy(auth = "a"), remoteDir.copy(cacheTtl = ttl)),
        )
        val repoDir = DirectorySource.RepoDir("d")
        assertContract(DirectorySource.RepoDir.serializer(), repoDir, listOf(repoDir.copy(path = "e"), repoDir.copy(ref = "main")))
        assertContract(DirectorySource.LocalDir.serializer(), DirectorySource.LocalDir("d"), listOf(DirectorySource.LocalDir("e")))
    }
}
