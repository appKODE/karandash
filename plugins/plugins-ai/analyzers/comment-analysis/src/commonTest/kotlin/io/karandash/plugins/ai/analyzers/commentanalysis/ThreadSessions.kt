@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.analyzers.commentanalysis

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.finding.ReviewNote
import io.karandash.api.finding.ReviewThread
import io.karandash.api.run.MergeRequestThreads
import io.karandash.api.run.RunServiceKeys
import io.karandash.models.finding.Finding
import kotlinx.coroutines.withContext

/** One open thread a person started, so the analyzer has a discussion to judge. */
internal val HUMAN_THREAD = ReviewThread("h1", listOf(ReviewNote("hn1", "Is this tested", author = "ann")))

/** Runs [CommentAnalysisAnalyzerModule.analyze] in a run whose review threads are [threads] and whose bot is [bot]. */
internal suspend fun CommentAnalysisAnalyzerModule.analyzeWithThreads(
    context: AiAnalyzerContext,
    threads: List<ReviewThread> = listOf(HUMAN_THREAD),
    bot: String? = null,
    botFails: Boolean = false,
): List<Finding> {
    val session = ModuleRegistryImpl().forRun()
    session.provide(RunServiceKeys.REVIEW_THREADS, FakeThreads(threads, bot, botFails))
    return try {
        withContext(session.services) { analyze(context) }
    } finally {
        session.close()
    }
}

internal class FakeThreads(
    private val threads: List<ReviewThread>,
    private val bot: String? = null,
    private val botFails: Boolean = false,
) : MergeRequestThreads {
    override suspend fun list() = threads

    override suspend fun botUsername() = if (botFails) error("no bot identity") else bot

    override suspend fun isMember(username: String) = false

    override suspend fun resolve(
        threadId: String,
        note: String,
    ) = Result.success(Unit)

    override suspend fun reply(
        threadId: String,
        body: String,
    ) = Result.success(Unit)
}
