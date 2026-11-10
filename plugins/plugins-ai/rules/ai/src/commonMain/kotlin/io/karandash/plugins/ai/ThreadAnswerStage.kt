@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai

import io.karandash.api.ai.AiService
import io.karandash.api.run.RunScopedServices
import io.karandash.api.run.RunServiceKeys
import io.karandash.logger.Logger
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.plugins.ai.core.AiDefaultModels
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.LanguagePromptOverlays
import io.karandash.plugins.ai.core.ThreadAnswers
import io.karandash.plugins.ai.core.ThreadAsker
import kotlinx.serialization.json.JsonElement

/**
 * Answers `@karandash why` and `ask` thread replies at the end of the run, once the report's findings and the run's
 * provenance are known ([ThreadAnswers]). Gated by `ai.feedback` like the other thread commands; `ask` also needs
 * `ai.thread-commands.ask` and an AI service, and spends the run's AI budget. An AI dry run (`ai.dry-run`,
 * `dry-run-scope: ai|all`) answers nothing and makes no model call.
 */
internal object ThreadAnswerStage {
    suspend fun run(
        config: AiPluginConfig,
        rawConfig: JsonElement?,
        aiService: AiService?,
        aiRule: AiRuleModule?,
        findings: List<Finding>,
        mr: MergeRequest,
        logger: Logger,
    ) {
        if (!config.feedbackEnabled) return
        if (config.dryRunAi) {
            logger.info("Dry run: thread answers (why, ask) skipped")
            return
        }
        val threads = RunScopedServices.current()?.get(RunServiceKeys.REVIEW_THREADS) ?: return
        val limits = aiRule?.runLimits()
        val language = LanguagePromptOverlays.responseLanguageOf(rawConfig)
        val asker = aiService?.takeIf { config.threadCommands.ask }?.let {
            val model = config.model ?: AiDefaultModels.cheapFor(config.provider)
            ThreadAsker(it, model, mr, language, limits?.first, limits?.second)
        }
        ThreadAnswers.answer(threads, findings, aiRule?.reviewProvenance(), asker, logger, config.threadCommands.ask, language)
    }
}
