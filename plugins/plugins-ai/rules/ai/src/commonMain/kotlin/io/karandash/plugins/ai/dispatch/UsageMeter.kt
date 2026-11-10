package io.karandash.plugins.ai.dispatch

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import io.karandash.models.finding.Finding
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import kotlinx.atomicfu.atomic

/**
 * The model and tokens of one analyzer run: [watch] records each completion, [stamp] puts them on the run's findings
 * as [AiFindingMetadataKeys.AI_MODEL] and [AiFindingMetadataKeys.AI_TOKENS_USED], unless the analyzer set them.
 */
internal class UsageMeter {
    private val model = atomic<String?>(null)
    private val tokens = atomic(0)

    fun watch(service: AiService): AiService =
        object : AiService by service {
            override suspend fun complete(
                request: AiCompletionRequest,
                providerId: String?,
            ): AiCompletionResponse =
                service.complete(request, providerId).also {
                    model.value = it.model
                    tokens.addAndGet(it.totalTokens)
                }
        }

    fun stamp(finding: Finding): Finding {
        val used = model.value ?: return finding
        val usage = mapOf(AiFindingMetadataKeys.AI_MODEL to used, AiFindingMetadataKeys.AI_TOKENS_USED to tokens.value.toString())
        return finding.copy(metadata = usage + finding.metadata)
    }
}
