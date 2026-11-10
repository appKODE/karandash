@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.core

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.errors.ProviderException
import io.karandash.api.exception.ProviderApiException
import io.karandash.api.platform.MergeRequestSearchHit
import io.karandash.api.run.MergeRequestSearch
import io.karandash.api.run.RunServiceKeys
import io.karandash.logger.Logger
import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.FakeAiService
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

/** the author's track record from the platform search feeds the author-familiarity process-risk factor. */
class AuthorHistoryTest {
    private val mr = createTestMergeRequest()

    private fun hit(
        id: String,
        state: String,
        author: String? = "testuser",
    ) = MergeRequestSearchHit(id = id, title = "t", state = state, author = author)

    @Test
    fun `merge rate counts only finished MRs by the author`() {
        val hits = listOf(
            hit("1", MergeRequestSearchHit.CLOSED),
            hit("2", MergeRequestSearchHit.MERGED),
            hit("3", MergeRequestSearchHit.MERGED),
            hit("4", MergeRequestSearchHit.CLOSED),
            hit("5", MergeRequestSearchHit.OPENED),
            hit("6", MergeRequestSearchHit.CLOSED, author = "other"),
        )
        assertEquals(MrHistory(authorMergeSuccessRate = 2.0 / 3), AuthorHistory.of(mr, hits))
        assertEquals(MrHistory(), AuthorHistory.of(mr, listOf(hit("5", MergeRequestSearchHit.OPENED))))
    }

    @Test
    fun `analyzers measure author familiarity from the run search`() =
        runTest {
            val context = AiAnalyzerContext(
                mr,
                FakeAiService("{}"),
                TokenBudget(1_000),
                "m",
                config = KarandashJson.Default.parseToJsonElement("{}"),
            )
            val session = ModuleRegistryImpl().forRun()
            session.provide(
                RunServiceKeys.MERGE_REQUEST_SEARCH,
                MergeRequestSearch { _, _, _ ->
                    listOf("2", "3", "4").map { hit(it, MergeRequestSearchHit.MERGED) } + hit("5", MergeRequestSearchHit.CLOSED)
                },
            )
            val measured = withContext(session.services) { TriageRisk.measure(context) }.also { session.close() }

            assertEquals(0.5, TriageRisk.measure(context).authorFamiliarity)
            assertEquals(0.25, measured.authorFamiliarity)
            val alwaysMerged = TriageRisk.of(context, MrHistory(authorMergeSuccessRate = 1.0))
            assertEquals(0.0, alwaysMerged.authorFamiliarity)
        }

    @Test
    fun `a failing platform search gives a neutral history and a warning`() =
        runTest {
            val failures = listOf(
                ProviderApiException(403, "Forbidden", "GitLab", "GET search", ""),
                ProviderApiException(429, "Too Many Requests", "GitLab", "GET search", ""),
                IOException("connection reset"),
                ProviderException("search failed"),
            )
            for (failure in failures) {
                val warnings = mutableListOf<String>()
                val session = ModuleRegistryImpl().forRun()
                session.provide(RunServiceKeys.MERGE_REQUEST_SEARCH, MergeRequestSearch { _, _, _ -> throw failure })
                val history = withContext(session.services) { AuthorHistory.load(mr, WarnLogger(warnings)) }.also { session.close() }

                assertEquals(MrHistory(), history)
                assertEquals(1, warnings.size, failure.toString())
            }
        }

    private class WarnLogger(
        private val warnings: MutableList<String>,
    ) : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) {
            warnings += message
        }
    }
}
