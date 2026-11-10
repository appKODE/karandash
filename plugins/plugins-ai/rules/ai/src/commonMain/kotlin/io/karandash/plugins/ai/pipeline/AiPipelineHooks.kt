package io.karandash.plugins.ai.pipeline

import io.karandash.api.support.rethrowCancellation
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.plugins.ai.hooks.AiHookExecutor
import io.karandash.plugins.ai.hooks.AiHookExecutor.Companion.HOOK_METADATA_PREFIX
import io.karandash.plugins.ai.hooks.AiHookExecutor.Companion.PER_FINDING
import io.karandash.plugins.ai.hooks.AiHookResult
import io.karandash.sdk.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** the `ai.hooks` of a run, called once per hook point by the AI pipeline extension. */
internal class AiPipelineHooks(
    private val executor: AiHookExecutor,
    private val logger: Logger,
) {
    /** Runs the `post-validation` and per-finding hooks; their findings are added to [findings]. */
    suspend fun postValidation(
        mr: MergeRequest,
        findings: List<Finding>,
    ): List<Finding> {
        @Suppress("TooGenericExceptionCaught") // boundary: hook execution must not crash pipeline
        val results = coroutineScope {
            listOf(
                async {
                    try {
                        executor.execute("post-validation", mr, findings) { msg -> logger.info(msg) }
                    } catch (e: Exception) {
                        // boundary: optional AI step must not fail the pipeline
                        e.rethrowCancellation()
                        logger.warn("AI hook 'post-validation' failed: ${e.message}")
                        null
                    }
                },
                async {
                    try {
                        executor.executePerFinding(mr, findings) { msg -> logger.info(msg) }
                    } catch (e: Exception) {
                        // boundary: optional AI step must not fail the pipeline
                        e.rethrowCancellation()
                        logger.warn("AI hook 'per-finding' failed: ${e.message}")
                        null
                    }
                },
            ).awaitAll().filterNotNull()
        }

        val merged = AiHookResult.merge(results)
        logComments(merged, "post-validation")
        val annotated = withHookMetadata(findings, merged.metadata)
        return if (merged.findings.isEmpty()) annotated else annotated + merged.findings
    }

    /**
     * Runs the hook at [hookPoint]; a failure is logged, never thrown. Returns [findings] carrying the hook's
     * `metadata` reply; a hook point without findings (`post-fetch`) logs the reply instead.
     */
    suspend fun run(
        hookPoint: String,
        mr: MergeRequest,
        findings: List<Finding>,
    ): List<Finding> {
        @Suppress("TooGenericExceptionCaught") // boundary: hook execution must not crash pipeline
        try {
            val result = executor.execute(hookPoint, mr, findings) { msg -> logger.info(msg) }
            logComments(result, hookPoint)
            if (findings.isEmpty()) result.metadata.forEach { (key, reply) -> logger.info("[ai-hook:$key:metadata] $reply") }
            return withHookMetadata(findings, result.metadata)
        } catch (e: Exception) {
            // boundary: optional AI step must not fail the pipeline
            e.rethrowCancellation()
            logger.warn("AI hook '$hookPoint' failed: ${e.message}")
            return findings
        }
    }

    /**
     * A `metadata` hook reply is stored on the findings it is about: the point's reply (`post-validation`,
     * `pre-report`) on every finding, a `per-finding` reply on the finding it was asked about.
     */
    private fun withHookMetadata(
        findings: List<Finding>,
        metadata: Map<String, String>,
    ): List<Finding> =
        if (metadata.isEmpty()) {
            findings
        } else {
            findings.map { finding ->
                val own = metadata
                    .filterKeys { it == AiHookExecutor.perFindingKey(finding) || !it.startsWith("$PER_FINDING:") }
                    .mapKeys { (key, _) -> HOOK_METADATA_PREFIX + key.substringBefore(':') }
                if (own.isEmpty()) finding else finding.copy(metadata = finding.metadata + own)
            }
        }

    private fun logComments(
        result: AiHookResult,
        hookPoint: String,
    ) {
        for (comment in result.comments) {
            logger.info("[ai-hook:$hookPoint:comment] $comment")
        }
    }
}
