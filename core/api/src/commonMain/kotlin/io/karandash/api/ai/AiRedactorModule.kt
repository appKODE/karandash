package io.karandash.api.ai

import io.karandash.api.Module
import io.karandash.api.ModuleType
import kotlinx.serialization.Serializable

/**
 * Redacts sensitive content from text before it is sent to an AI provider.
 *
 * Redactors run in [redactorPriority] order (ascending, lowest first)
 * and perform pure text transformations.
 *
 * The built-in [SecretRedactor][io.karandash.plugins.ai.core.SecretRedactor] handles
 * common secret patterns. Third-party redactors can add organization-specific patterns
 * (e.g. internal hostnames, proprietary identifiers).
 *
 * Redactors operate on the **prompt text** inside [AiCompletionRequest],
 * not on the MR model directly (MR-level redaction is handled separately).
 */
public interface AiRedactorModule : Module {
    override val type: ModuleType get() = ModuleType.AI_REDACTOR

    /** Sort priority - lower values run first. Default is 100. */
    public val redactorPriority: Int get() = 100

    /**
     * Redact sensitive content from the given text.
     *
     * Suspending so bridged (JS/WASM/native/JAR) redactors call their transport
     * without blocking a thread.
     *
     * @param text The text to redact.
     * @param context Metadata about the redaction call.
     * @return The redacted text.
     */
    public suspend fun redact(
        text: String,
        context: AiRedactorContext
    ): String
}

/**
 * Context passed to redactors.
 *
 * @param ruleId The rule ID being evaluated, if known.
 * @param providerId The target provider ID, if known.
 */
@Serializable
public data class AiRedactorContext(
    val ruleId: String?,
    val providerId: String?,
)
