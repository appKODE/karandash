@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai

import io.karandash.api.ai.AiService
import io.karandash.api.exception.KarandashException
import io.karandash.api.finding.InlineFindingMarker
import io.karandash.api.run.DismissedInlineComments
import io.karandash.api.run.RunScopedServices
import io.karandash.api.run.RunServiceKeys
import io.karandash.api.support.rethrowCancellation
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.models.MergeRequest
import io.karandash.models.MrCacheKey
import io.karandash.plugins.ai.core.AiDefaultModels
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.FeedbackStore
import io.karandash.plugins.ai.core.LlmReplyJudge
import io.karandash.plugins.ai.core.ReplyJudge
import io.karandash.plugins.ai.core.ReplyJudgeMode
import io.karandash.plugins.ai.core.ThreadFeedback
import io.karandash.state.snapshot.SnapshotManager
import kotlinx.coroutines.sync.Semaphore
import kotlinx.io.IOException

/**
 * Loads and saves the per-MR state of [AiRuleModule]: delta snapshot, feedback dismissals and
 * the provider response cache, keyed by [MrCacheKey].
 */
internal object AiMrStatePersistence {
    /** Returns [state] bound to [mr]'s key with its snapshot, feedback and response cache loaded. */
    suspend fun load(
        mr: MergeRequest,
        state: AiRuleInitState,
        configHash: Long?,
    ): AiRuleInitState {
        val key = SnapshotManager.mrKey(mr)
        val snapshotManager = SnapshotManager(state.statePersistence, key, state.runtimeContext.logger, configHash)
        snapshotManager.load()
        val persistence = state.statePersistence
        val feedbackStore = if (persistence != null && state.pluginCfg.feedbackEnabled) {
            state.runtimeContext.logger.debug("AI plugin: loading feedback state for $key")
            // Persisted dismissals win on fingerprint clashes; `ai.feedback` entries from config are kept.
            val persisted = persistence.loadFeedback(key)
            FeedbackStore
                .fromEntries(
                    (persisted.toSerializable() + state.feedbackStore.toSerializable()).distinctBy { it.fingerprint },
                    persisted.acceptedAnchors() + state.feedbackStore.acceptedAnchors(),
                ).also {
                    state.runtimeContext.log("AI plugin: feedback state loaded -- ${it.dismissCount()} dismissed finding(s)")
                }
        } else {
            state.feedbackStore
        }
        if (state.pluginCfg.feedbackEnabled) {
            applyPlatformDismissals(feedbackStore, state.runtimeContext.logger)
            val judge = replyJudge(state.pluginCfg, state.aiService, state.concurrencySemaphore, state.runtimeContext.logger)
            val commits = mr.commits.map { it.sha }.toSet()
            applyThreadFeedback(feedbackStore, state.runtimeContext.logger, state.pluginCfg.dryRunAi, judge, commits)
        }
        val cp = state.cachingProvider
        if (persistence != null && cp != null) {
            val cached = persistence.loadResponseCache(key)
            if (cached.isNotEmpty()) {
                cp.importCache(cached)
                state.runtimeContext.log("AI plugin: Loaded ${cached.size} cached responses for $key")
            }
        }
        return state.copy(currentMrKey = key, snapshotManager = snapshotManager, feedbackStore = feedbackStore)
    }

    /**
     * Records the findings of inline comments a reviewer dismissed on the platform (thumbs-down, a thread resolved by a
     * person, Azure "won't fix"). A finding already in [store] is skipped, so a standing reaction counts once.
     */
    internal suspend fun applyPlatformDismissals(
        store: FeedbackStore,
        logger: Logger,
    ) {
        val source = RunScopedServices.current()?.get(RunServiceKeys.DISMISSED_INLINE_COMMENTS) ?: return
        val bodies = readDismissedBodies(source, logger) ?: return
        // Outside the read's try: recording into the store is not a platform read failure.
        applyDismissedBodies(store, logger, bodies)
    }

    /**
     * Learns from replies and `@karandash` commands in the run MR's karandash threads ([ThreadFeedback]). Re-derived from
     * the platform each run; nothing when the run has no review threads service. Replies the keyword classifier
     * leaves unknown go to [judge] when one is given. A cited sha counts as a fix only when it is one of [commits].
     */
    internal suspend fun applyThreadFeedback(
        store: FeedbackStore,
        logger: Logger,
        dryRun: Boolean = false,
        judge: ReplyJudge? = null,
        commits: Set<String>? = null,
    ) {
        val threads = RunScopedServices.current()?.get(RunServiceKeys.REVIEW_THREADS) ?: return
        ThreadFeedback.ingest(store, threads, logger, dryRun, judge, commits)
    }

    /**
     * The run's reply judge for `ai.feedback.reply-judge: llm`: the cheap model of the configured provider unless
     * `ai.model` is set, capped at [LlmReplyJudge.DEFAULT_MAX_CALLS] calls. Null when off, in a dry run (no model
     * calls) or without an AI service.
     */
    internal fun replyJudge(
        config: AiPluginConfig,
        aiService: AiService?,
        permits: Semaphore? = null,
        logger: Logger = NoOpLogger,
    ): ReplyJudge? {
        if (config.feedbackReplyJudge != ReplyJudgeMode.LLM || config.dryRunAi || aiService == null) return null
        val model = config.model ?: AiDefaultModels.cheapFor(config.provider)
        return LlmReplyJudge(aiService, model, permits = permits, logger = logger)
    }

    /** The dismissed comment bodies, or `null` when the platform gives none or the read failed (logged at warn). */
    private suspend fun readDismissedBodies(
        source: DismissedInlineComments,
        logger: Logger,
    ): List<String>? {
        val failure: Exception = try {
            return source.list()
        } catch (e: KarandashException) {
            e
        } catch (e: IOException) {
            e
        } catch (e: IllegalStateException) {
            e.rethrowCancellation()
            e
        }
        logger.warn("ai.feedback: reading dismissed inline comments failed: ${failure.message}")
        return null
    }

    private fun applyDismissedBodies(
        store: FeedbackStore,
        logger: Logger,
        bodies: List<String>,
    ) {
        val fresh = bodies
            .flatMap { InlineFindingMarker.parse(it) }
            .distinct()
            .filterNot { store.isDismissed(it.rule, it.path, it.messageHash) }
        fresh.forEach { store.dismiss(it.rule, it.path, it.messageHash, it.messagePrefix, it.semanticFingerprint, it.fpv) }
        if (fresh.isNotEmpty()) logger.info("ai.feedback: ${fresh.size} finding(s) dismissed by reviewers on the platform")
    }

    /** Persists feedback dismissals and the response cache for the MR [state] was bound to, if any. */
    suspend fun save(state: AiRuleInitState) {
        val persistence = state.statePersistence ?: return
        val key = state.currentMrKey ?: return
        if (state.pluginCfg.feedbackEnabled) {
            state.runtimeContext.logger.debug("AI plugin: saving feedback state for $key")
            persistence.saveFeedback(key, state.feedbackStore)
            val savedCount = state.feedbackStore.dismissCount()
            state.runtimeContext.log("AI plugin: feedback state saved -- $savedCount dismissed finding(s)")
        }
        val cp = state.cachingProvider
        if (cp != null) {
            persistence.saveResponseCache(key, cp.exportCache())
        }
    }
}
