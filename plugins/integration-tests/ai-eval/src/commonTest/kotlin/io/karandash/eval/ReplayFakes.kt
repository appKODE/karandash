@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.eval

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiInterceptChain
import io.karandash.api.ai.AiInterceptContext
import io.karandash.api.ai.AiInterceptorModule
import io.karandash.api.ai.AiProviderException
import io.karandash.api.ai.AiProviderModule
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.MergeRequestSearchHit
import io.karandash.api.run.MergeRequestThreads
import io.karandash.logger.Logger
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Innermost AI interceptor: records every request exactly as the provider would receive it (after
 * prompt processors and redactors) and answers it from the case cassette instead of calling out.
 */
class CassetteInterceptor(
    private val caseId: String,
    private val responder: EvalRunner.Responder,
) : AiInterceptorModule {
    override val id: String = "io.karandash.eval.cassette-interceptor"
    override val name: String = "Eval cassette"
    override val version: String = "1.0.0"
    override val interceptorPriority: Int = Int.MAX_VALUE

    val prompts = MutableStateFlow<List<ReplayPrompt>>(emptyList())
    val misses = MutableStateFlow<List<String>>(emptyList())
    val estimatedTokens = MutableStateFlow(0)

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun intercept(
        request: AiCompletionRequest,
        context: AiInterceptContext,
        chain: AiInterceptChain,
    ): AiCompletionResponse {
        val analyzer = context.ruleId ?: UNKNOWN
        prompts.update { it + ReplayPrompt(analyzer, request.systemPrompt, request.prompt) }
        val content = responder.respond(caseId, analyzer, request)
        if (content == null) {
            misses.update { it + "cassette miss: $analyzer" }
            throw AiProviderException("$caseId: no cassette response for $analyzer")
        }
        val promptTokens = (request.prompt.length + request.systemPrompt.orEmpty().length) / CHARS_PER_TOKEN
        val completionTokens = content.length / CHARS_PER_TOKEN
        estimatedTokens.update { it + promptTokens + completionTokens }
        return AiCompletionResponse(content, promptTokens, completionTokens, promptTokens + completionTokens, request.model)
    }

    companion object {
        private const val CHARS_PER_TOKEN = 4
        private const val UNKNOWN = "unknown"
    }
}

/** The configured provider; reaching it means a call bypassed the interceptor chain. */
class CassetteProvider : AiProviderModule {
    override val id: String = "io.karandash.eval.cassette-provider"
    override val name: String = "Eval cassette provider"
    override val version: String = "1.0.0"
    override val providerId: String = PROVIDER_ID
    override val defaultModel: String = EvalRunner.MODEL

    val bypasses = MutableStateFlow<List<String>>(emptyList())

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun complete(request: AiCompletionRequest): AiCompletionResponse {
        bypasses.update { it + "provider reached without interceptor" }
        throw AiProviderException("eval replay: provider called directly, bypassing the interceptor chain")
    }

    companion object {
        const val PROVIDER_ID = "eval-cassette"
    }
}

/** Platform that records the summary comment and inline notes instead of posting them. */
class CassettePlatform(
    private val case: EvalCase,
) : PlatformServiceModule {
    override val id: String = "io.karandash.eval.$PLATFORM"
    override val name: String = "Eval cassette platform"
    override val version: String = "1.0.0"
    override val type: ModuleType = ModuleType.PLATFORM_SERVICE

    val summaries = MutableStateFlow<List<String>>(emptyList())
    val notes = MutableStateFlow<List<ReplayNote>>(emptyList())

    override suspend fun initialize(context: PluginContext): Boolean = true

    override val supportsFileContent: Boolean get() = case.repositoryFiles.isNotEmpty()

    override suspend fun fetchFileContent(
        projectId: String,
        ref: String,
        path: String,
    ): String? = case.repositoryFiles[path]

    override suspend fun searchMergeRequests(
        projectId: String,
        text: String?,
        author: String?,
        limit: Int,
    ): List<MergeRequestSearchHit>? = case.pastMergeRequests.takeIf { it.isNotEmpty() }?.take(limit)

    override suspend fun fetchMergeRequest(
        projectId: String,
        mrId: String,
    ): MergeRequest = error("eval replay passes the MR via mrOverride")

    override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = error("eval replay passes the MR via mrOverride")

    override suspend fun addLabels(
        projectId: String,
        mrId: String,
        labels: List<String>,
    ) = Unit

    override suspend fun removeLabels(
        projectId: String,
        mrId: String,
        labels: List<String>,
    ) = Unit

    override suspend fun setDraft(
        projectId: String,
        mrId: String,
        draft: Boolean,
    ) = Unit

    override suspend fun updateTitle(
        projectId: String,
        mrId: String,
        title: String,
    ) = Unit

    override suspend fun updateDescription(
        projectId: String,
        mrId: String,
        description: String,
    ) = Unit

    override suspend fun approve(
        projectId: String,
        mrId: String,
    ) = Unit

    override suspend fun unapprove(
        projectId: String,
        mrId: String,
    ) = Unit

    override suspend fun postComment(
        projectId: String,
        mrId: String,
        body: String,
    ): String {
        summaries.update { it + body }
        return "summary-${summaries.value.size}"
    }

    override suspend fun updateComment(
        projectId: String,
        mrId: String,
        commentId: String,
        body: String,
    ) {
        summaries.update { it + body }
    }

    override suspend fun findExistingComment(
        projectId: String,
        mrId: String,
        marker: String,
    ): String? = null

    override suspend fun findAllMatchingComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): List<String> = emptyList()

    override suspend fun postInlineComment(
        projectId: String,
        mrId: String,
        body: String,
        filePath: String,
        line: Int,
        diffRefs: DiffRefs,
        startLine: Int?,
    ): String {
        notes.update { it + ReplayNote(filePath, line, startLine, body) }
        return "note-${notes.value.size}"
    }

    override suspend fun renderSuggestionBlock(suggestedCode: String): String = "```suggestion\n$suggestedCode\n```"

    companion object {
        const val PLATFORM = "cassette-platform"
    }
}

/** Keeps every log line of one replayed run; written to `build/reports/ai-eval/replay/<id>.log`. */
class ReplayLogger : Logger {
    val lines = MutableStateFlow<List<String>>(emptyList())

    override val isDebugEnabled: Boolean get() = true

    override fun debug(message: String) = lines.update { it + "DEBUG $message" }

    override fun info(message: String) = lines.update { it + "INFO $message" }

    override fun warn(message: String) = lines.update { it + "WARN $message" }

    override fun error(message: String) = lines.update { it + "ERROR $message" }
}

/** The case's [EvalCase.reviewThreads]; resolving and replying succeed without effect. */
class CaseThreads(
    private val case: EvalCase,
) : MergeRequestThreads {
    override suspend fun list() = case.reviewThreads

    override suspend fun botUsername(): String? = null

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
