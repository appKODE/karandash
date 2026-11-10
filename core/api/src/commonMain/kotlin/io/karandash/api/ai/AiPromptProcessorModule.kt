package io.karandash.api.ai

import io.karandash.api.Module
import io.karandash.api.ModuleType
import kotlinx.serialization.Serializable

/**
 * Pre-processes AI prompts before they enter the interceptor chain.
 *
 * Prompt processors run in [processorPriority] order (ascending, lowest first)
 * and can rewrite, augment, or translate the user/system prompt text.
 *
 * Example use-cases: injecting organization-specific guidelines, translating prompts
 * to another language, or adding few-shot examples.
 */
public interface AiPromptProcessorModule : Module {
    override val type: ModuleType get() = ModuleType.AI_PROMPT_PROCESSOR

    /** Sort priority - lower values run first. Default is 100. */
    public val processorPriority: Int get() = 100

    /**
     * Transform the user prompt.
     *
     * @param prompt Current prompt text.
     * @param context Metadata about the completion call origin.
     * @return Transformed prompt text.
     */
    public suspend fun processPrompt(
        prompt: String,
        context: AiPromptProcessorContext
    ): String

    /**
     * Transform the system prompt.
     *
     * @param systemPrompt Current system prompt text (may be null).
     * @param context Metadata about the completion call origin.
     * @return Transformed system prompt text, or null to leave unset.
     */
    public suspend fun processSystemPrompt(
        systemPrompt: String?,
        context: AiPromptProcessorContext,
    ): String? = systemPrompt
}

/**
 * Context passed to prompt processors with metadata about the call origin.
 *
 * @param ruleId The rule ID being evaluated, if known.
 * @param model The target model identifier.
 * @param providerId The target provider ID, if known.
 */
@Serializable
public data class AiPromptProcessorContext(
    val ruleId: String?,
    val model: String,
    val providerId: String?,
)
