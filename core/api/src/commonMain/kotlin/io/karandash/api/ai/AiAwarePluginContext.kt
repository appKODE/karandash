package io.karandash.api.ai

import io.karandash.api.PluginContext

/**
 * Extended [PluginContext] that exposes the platform [AiService].
 *
 * Modules opt in to AI capabilities via safe cast:
 * ```kotlin
 * val ai = (context as? AiAwarePluginContext)?.ai ?: return
 * val response = ai.complete(request)
 * ```
 *
 * This is a sub-interface to keep [PluginContext] stable - existing plugins,
 * tests, and external implementations are unaffected.
 */
public interface AiAwarePluginContext : PluginContext {
    /** The platform AI service, or null if no AI provider is configured. */
    public val ai: AiService?
}
